package com.nuvio.app

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * One Nuvio per data directory.
 *
 * Nothing stopped a second copy of the app from starting against the same `NuvioHTPC` folder, and on
 * 2026-09-22 one had been sitting there for five hours — window long gone, process alive — while the
 * user relaunched the app nine times. Every shared file behaved badly in its own way: the log
 * rotation failed on the orphan's handle and went silent, Coil's disk-cache journal could not be
 * rebuilt so no poster was written to disk all evening, and both processes were saving the same
 * `nuvio_*.properties` stores over each other.
 *
 * The primary instance holds an OS file lock on `instance.lock` for its whole life and listens on an
 * ephemeral loopback port whose number it publishes in `instance.port`. A later launch fails the
 * lock, sends `activate` to that port and exits; the primary brings its window back (from the tray
 * if need be). The lock, not the port file, is the authority: it is released by the OS the moment
 * the process dies, so a crash never leaves a stale claim behind.
 */
object DesktopSingleInstance {

    sealed interface Outcome {
        /** This process owns the data directory; carry on starting. */
        data object Primary : Outcome

        /** Another live process owns it. [signalled] is whether it acknowledged the activate request. */
        data class Secondary(val signalled: Boolean) : Outcome
    }

    private const val LockFileName = "instance.lock"
    private const val PortFileName = "instance.port"
    private const val ActivateCommand = "activate"
    private const val AcknowledgeReply = "ok"
    private const val ConnectTimeoutMs = 1_000
    private const val ReplyTimeoutMs = 3_000
    private const val PortFileRetries = 15
    private const val PortFileRetryDelayMs = 200L

    // Held for the life of the process so the lock is never garbage-collected away.
    @Volatile private var lockChannel: FileChannel? = null
    @Volatile private var lock: FileLock? = null
    @Volatile private var server: ServerSocket? = null

    /** Installed by Main once the window exists; runs on whatever thread received the signal. */
    @Volatile var onActivate: (() -> Unit)? = null

    /**
     * Claims [dataDir] for this process, or hands the launch to the process that already owns it.
     * Never throws: any I/O surprise resolves to [Outcome.Primary], because refusing to start over a
     * broken lock file would be worse than the duplicate it is guarding against.
     */
    fun claim(dataDir: Path): Outcome = runCatching { claimOrThrow(dataDir) }.getOrElse { error ->
        System.err.println("Warn: (SingleInstance) lock unavailable, starting anyway: $error")
        Outcome.Primary
    }

    private fun claimOrThrow(dataDir: Path): Outcome {
        Files.createDirectories(dataDir)
        val lockPath = dataDir.resolve(LockFileName)
        val channel = FileChannel.open(
            lockPath,
            StandardOpenOption.CREATE,
            StandardOpenOption.READ,
            StandardOpenOption.WRITE,
        )
        val acquired = try {
            channel.tryLock()
        } catch (_: OverlappingFileLockException) {
            null
        }
        if (acquired == null) {
            channel.close()
            return Outcome.Secondary(signalled = signalPrimary(dataDir.resolve(PortFileName)))
        }
        lockChannel = channel
        lock = acquired
        startListener(dataDir.resolve(PortFileName))
        return Outcome.Primary
    }

    private fun startListener(portFile: Path) {
        val socket = ServerSocket(0, 4, InetAddress.getLoopbackAddress())
        server = socket
        writePortFile(portFile, socket.localPort)
        Thread({
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (_: Throwable) {
                    break
                }
                runCatching { serve(client) }
            }
        }, "nuvio-single-instance").apply { isDaemon = true }.start()
        Runtime.getRuntime().addShutdownHook(Thread {
            runCatching { socket.close() }
            runCatching { Files.deleteIfExists(portFile) }
            runCatching { lock?.release() }
            runCatching { lockChannel?.close() }
        })
    }

    private fun serve(client: Socket) {
        client.use {
            it.soTimeout = ReplyTimeoutMs
            val reader = BufferedReader(InputStreamReader(it.getInputStream(), StandardCharsets.UTF_8))
            val command = reader.readLine()?.trim()
            if (command == ActivateCommand) {
                System.out.println("Info: (SingleInstance) second launch detected; bringing this window forward")
                runCatching { onActivate?.invoke() }
            }
            OutputStreamWriter(it.getOutputStream(), StandardCharsets.UTF_8).apply {
                write(AcknowledgeReply)
                write("\n")
                flush()
            }
        }
    }

    /** Written atomically so a reader never sees a half-written number. */
    private fun writePortFile(portFile: Path, port: Int) {
        val staging = portFile.resolveSibling("$PortFileName.tmp")
        Files.writeString(staging, "$port\n")
        Files.move(staging, portFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    /**
     * Asks the owning process to show itself. The port file may not exist yet if the owner is itself
     * still starting up (lock taken, socket not yet bound), so this waits a few seconds for it.
     */
    private fun signalPrimary(portFile: Path): Boolean {
        repeat(PortFileRetries) { attempt ->
            val port = runCatching { Files.readString(portFile).trim().toInt() }.getOrNull()
            if (port != null) {
                val delivered = runCatching {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), ConnectTimeoutMs)
                        socket.soTimeout = ReplyTimeoutMs
                        OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8).apply {
                            write(ActivateCommand)
                            write("\n")
                            flush()
                        }
                        val reply = BufferedReader(
                            InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8),
                        ).readLine()
                        reply?.trim() == AcknowledgeReply
                    }
                }.getOrDefault(false)
                if (delivered) return true
            }
            if (attempt < PortFileRetries - 1) Thread.sleep(PortFileRetryDelayMs)
        }
        return false
    }
}

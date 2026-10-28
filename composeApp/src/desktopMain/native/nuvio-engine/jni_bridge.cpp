// Copy of nuvio-engine/platform/android/engine/src/main/cpp/jni_bridge.cpp (v0.1.6) with one
// desktop change, marked "Desktop change" below. Re-copy and re-apply it on an engine bump.
#include <jni.h>

#include "nuvio_engine/nuvio_engine.h"

#include <array>
#include <cstdint>
#include <string>

namespace {

nuvio_engine* engine_from_handle(const jlong handle) {
    return reinterpret_cast<nuvio_engine*>(static_cast<std::uintptr_t>(handle));
}

jlong handle_from_engine(nuvio_engine* const engine) {
    return static_cast<jlong>(reinterpret_cast<std::uintptr_t>(engine));
}

bool read_utf8(JNIEnv* const env, jstring value, std::string& output) {
    if (value == nullptr) {
        return false;
    }
    const auto string_class = env->FindClass("java/lang/String");
    if (string_class == nullptr) {
        return false;
    }
    const auto get_bytes = env->GetMethodID(
        string_class,
        "getBytes",
        "(Ljava/lang/String;)[B"
    );
    const auto charset = env->NewStringUTF("UTF-8");
    if (get_bytes == nullptr || charset == nullptr) {
        env->DeleteLocalRef(string_class);
        return false;
    }
    const auto bytes = static_cast<jbyteArray>(env->CallObjectMethod(value, get_bytes, charset));
    env->DeleteLocalRef(charset);
    env->DeleteLocalRef(string_class);
    if (env->ExceptionCheck() || bytes == nullptr) {
        return false;
    }
    const auto length = env->GetArrayLength(bytes);
    output.resize(static_cast<std::size_t>(length));
    if (length > 0) {
        env->GetByteArrayRegion(
            bytes,
            0,
            length,
            reinterpret_cast<jbyte*>(output.data())
        );
    }
    env->DeleteLocalRef(bytes);
    return !env->ExceptionCheck() && output.find('\0') == std::string::npos;
}

jstring make_utf8(JNIEnv* const env, const char* const text) {
    const auto length = static_cast<jsize>(std::char_traits<char>::length(text));
    const auto bytes = env->NewByteArray(length);
    if (bytes == nullptr) {
        return nullptr;
    }
    if (length > 0) {
        env->SetByteArrayRegion(
            bytes,
            0,
            length,
            reinterpret_cast<const jbyte*>(text)
        );
    }
    const auto string_class = env->FindClass("java/lang/String");
    const auto constructor = string_class == nullptr
        ? nullptr
        : env->GetMethodID(string_class, "<init>", "([BLjava/lang/String;)V");
    const auto charset = env->NewStringUTF("UTF-8");
    jstring result = nullptr;
    if (constructor != nullptr && charset != nullptr) {
        result = static_cast<jstring>(
            env->NewObject(string_class, constructor, bytes, charset)
        );
    }
    if (charset != nullptr) {
        env->DeleteLocalRef(charset);
    }
    if (string_class != nullptr) {
        env->DeleteLocalRef(string_class);
    }
    env->DeleteLocalRef(bytes);
    return result;
}

jlongArray make_command_result(
    JNIEnv* const env,
    const nuvio_engine_status status,
    const std::uint64_t request_id
) {
    const std::array<jlong, 2> values{
        static_cast<jlong>(status),
        static_cast<jlong>(request_id),
    };
    const auto result = env->NewLongArray(static_cast<jsize>(values.size()));
    if (result != nullptr) {
        env->SetLongArrayRegion(result, 0, static_cast<jsize>(values.size()), values.data());
    }
    return result;
}

void throw_illegal_state(JNIEnv* const env, const char* const message) {
    const auto exception_class = env->FindClass("java/lang/IllegalStateException");
    if (exception_class != nullptr) {
        env->ThrowNew(exception_class, message);
        env->DeleteLocalRef(exception_class);
    }
}

}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_nuvio_engine_internal_NativeBridge_nativeCreate(
    JNIEnv* const env,
    jobject,
    jstring data_directory,
    jstring cache_directory,
    const jlong memory_cache_capacity,
    const jlong disk_cache_capacity,
    const jint torrent_profile,
    const jint listen_port,
    const jint upload_mode,
    const jlong upload_limit,
    const jint stream_inactivity_timeout,
    const jint warm_torrent_timeout,
    jstring tls_ca_bundle_path_value,
    const jboolean ram_only_mode
) {
    std::string data_path;
    std::string cache_path;
    std::string tls_ca_bundle_path;
    if (!read_utf8(env, data_directory, data_path)
        || !read_utf8(env, cache_directory, cache_path)
        || !read_utf8(env, tls_ca_bundle_path_value, tls_ca_bundle_path)) {
        return make_command_result(env, NUVIO_ENGINE_STATUS_INVALID_ARGUMENT, 0);
    }

    nuvio_engine_config config{};
    nuvio_engine_config_init(&config);
    config.data_directory = data_path.c_str();
    config.cache_directory = cache_path.c_str();
    config.memory_cache_capacity_bytes = static_cast<std::uint64_t>(memory_cache_capacity);
    config.disk_cache_capacity_bytes = static_cast<std::uint64_t>(disk_cache_capacity);
    config.torrent_profile = static_cast<nuvio_engine_torrent_profile>(torrent_profile);
    config.listen_port = static_cast<std::uint16_t>(listen_port);
    config.upload_mode = static_cast<nuvio_engine_upload_mode>(upload_mode);
    config.upload_limit_bytes_per_second = static_cast<std::uint64_t>(upload_limit);
    config.stream_inactivity_timeout_milliseconds =
        static_cast<std::uint32_t>(stream_inactivity_timeout);
    config.warm_torrent_timeout_milliseconds =
        static_cast<std::uint32_t>(warm_torrent_timeout);
    // Desktop change vs. the Android bridge: an empty path means "no bundle", which the engine
    // only accepts as nullptr (an empty string is INVALID_ARGUMENT). Without a bundle the Windows
    // build validates TLS peers against the live system certificate store.
    config.tls_ca_bundle_path = tls_ca_bundle_path.empty() ? nullptr : tls_ca_bundle_path.c_str();
    // Desktop change: RAM-only mode (patches/0005-desktop-ram-only-mode.patch).
    config.ram_only_mode = ram_only_mode != JNI_FALSE ? 1U : 0U;

    nuvio_engine* engine = nullptr;
    const auto status = nuvio_engine_create(&config, &engine);
    return make_command_result(env, status, static_cast<std::uint64_t>(handle_from_engine(engine)));
}

extern "C" JNIEXPORT void JNICALL
Java_com_nuvio_engine_internal_NativeBridge_nativeDestroy(
    JNIEnv*,
    jobject,
    const jlong handle
) {
    nuvio_engine_destroy(engine_from_handle(handle));
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_nuvio_engine_internal_NativeBridge_nativeAddMagnet(
    JNIEnv* const env,
    jobject,
    const jlong handle,
    jstring magnet_uri
) {
    std::string magnet;
    if (!read_utf8(env, magnet_uri, magnet)) {
        return make_command_result(env, NUVIO_ENGINE_STATUS_INVALID_ARGUMENT, 0);
    }
    nuvio_engine_torrent_request request{};
    nuvio_engine_torrent_request_init(&request);
    request.source_type = NUVIO_ENGINE_TORRENT_SOURCE_MAGNET;
    request.magnet_uri = magnet.c_str();
    std::uint64_t request_id = 0;
    const auto status = nuvio_engine_add_torrent(
        engine_from_handle(handle),
        &request,
        &request_id
    );
    return make_command_result(env, status, request_id);
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_nuvio_engine_internal_NativeBridge_nativeAddTorrentData(
    JNIEnv* const env,
    jobject,
    const jlong handle,
    jbyteArray torrent_data
) {
    if (torrent_data == nullptr) {
        return make_command_result(env, NUVIO_ENGINE_STATUS_INVALID_ARGUMENT, 0);
    }
    const auto length = env->GetArrayLength(torrent_data);
    auto* const bytes = env->GetByteArrayElements(torrent_data, nullptr);
    if (bytes == nullptr) {
        return make_command_result(env, NUVIO_ENGINE_STATUS_ALLOCATION_FAILED, 0);
    }
    nuvio_engine_torrent_request request{};
    nuvio_engine_torrent_request_init(&request);
    request.source_type = NUVIO_ENGINE_TORRENT_SOURCE_DATA;
    request.torrent_data = reinterpret_cast<const std::uint8_t*>(bytes);
    request.torrent_data_size = static_cast<std::size_t>(length);
    std::uint64_t request_id = 0;
    const auto status = nuvio_engine_add_torrent(
        engine_from_handle(handle),
        &request,
        &request_id
    );
    env->ReleaseByteArrayElements(torrent_data, bytes, JNI_ABORT);
    return make_command_result(env, status, request_id);
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_nuvio_engine_internal_NativeBridge_nativePollEvent(
    JNIEnv* const env,
    jobject,
    const jlong handle
) {
    nuvio_engine_event event{};
    nuvio_engine_event_init(&event);
    const auto status = nuvio_engine_poll_event(engine_from_handle(handle), &event);
    if (status == NUVIO_ENGINE_STATUS_NO_EVENT) {
        return nullptr;
    }
    if (status != NUVIO_ENGINE_STATUS_OK) {
        throw_illegal_state(env, nuvio_engine_status_message(status));
        return nullptr;
    }

    const auto payload_class = env->FindClass("com/nuvio/engine/internal/NativeEventPayload");
    const auto constructor = payload_class == nullptr
        ? nullptr
        : env->GetMethodID(
            payload_class,
            "<init>",
            "(IJJJLjava/lang/String;Ljava/lang/String;IJLjava/lang/String;Ljava/lang/String;)V"
        );
    if (constructor == nullptr) {
        return nullptr;
    }
    const auto torrent_id = make_utf8(env, event.torrent_id);
    const auto message = make_utf8(env, event.message);
    const auto stream_id = make_utf8(env, event.stream_id);
    const auto stream_url = make_utf8(env, event.stream_url);
    const auto result = env->NewObject(
        payload_class,
        constructor,
        static_cast<jint>(event.type),
        static_cast<jlong>(event.sequence),
        static_cast<jlong>(event.request_id),
        static_cast<jlong>(event.dropped_events),
        torrent_id,
        message,
        static_cast<jint>(event.file_index),
        static_cast<jlong>(event.file_size),
        stream_id,
        stream_url
    );
    env->DeleteLocalRef(torrent_id);
    env->DeleteLocalRef(message);
    env->DeleteLocalRef(stream_id);
    env->DeleteLocalRef(stream_url);
    env->DeleteLocalRef(payload_class);
    return result;
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_nuvio_engine_internal_NativeBridge_nativeGetFiles(
    JNIEnv* const env,
    jobject,
    const jlong handle,
    jstring torrent_id_value
) {
    std::string torrent_id;
    if (!read_utf8(env, torrent_id_value, torrent_id)) {
        return nullptr;
    }
    std::size_t file_count = 0;
    auto status = nuvio_engine_get_file_count(
        engine_from_handle(handle),
        torrent_id.c_str(),
        &file_count
    );

    const auto file_class = env->FindClass("com/nuvio/engine/internal/NativeFilePayload");
    const auto file_constructor = file_class == nullptr
        ? nullptr
        : env->GetMethodID(file_class, "<init>", "(IJJZLjava/lang/String;)V");
    if (file_constructor == nullptr) {
        return nullptr;
    }
    const auto files = env->NewObjectArray(
        status == NUVIO_ENGINE_STATUS_OK ? static_cast<jsize>(file_count) : 0,
        file_class,
        nullptr
    );
    if (files == nullptr) {
        env->DeleteLocalRef(file_class);
        return nullptr;
    }
    if (status == NUVIO_ENGINE_STATUS_OK) {
        for (std::size_t index = 0; index < file_count; ++index) {
            nuvio_engine_file file{};
            nuvio_engine_file_init(&file);
            status = nuvio_engine_get_file(
                engine_from_handle(handle),
                torrent_id.c_str(),
                index,
                &file
            );
            if (status != NUVIO_ENGINE_STATUS_OK) {
                break;
            }
            const auto path = make_utf8(env, file.path);
            const auto payload = env->NewObject(
                file_class,
                file_constructor,
                static_cast<jint>(file.index),
                static_cast<jlong>(file.offset),
                static_cast<jlong>(file.size),
                static_cast<jboolean>(file.path_truncated != 0),
                path
            );
            env->SetObjectArrayElement(files, static_cast<jsize>(index), payload);
            env->DeleteLocalRef(payload);
            env->DeleteLocalRef(path);
        }
    }
    env->DeleteLocalRef(file_class);

    const auto result_class = env->FindClass("com/nuvio/engine/internal/NativeFilesPayload");
    const auto result_constructor = result_class == nullptr
        ? nullptr
        : env->GetMethodID(
            result_class,
            "<init>",
            "(I[Lcom/nuvio/engine/internal/NativeFilePayload;)V"
        );
    if (result_constructor == nullptr) {
        env->DeleteLocalRef(files);
        return nullptr;
    }
    const auto result = env->NewObject(
        result_class,
        result_constructor,
        static_cast<jint>(status),
        files
    );
    env->DeleteLocalRef(result_class);
    env->DeleteLocalRef(files);
    return result;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_nuvio_engine_internal_NativeBridge_nativePrepareStream(
    JNIEnv* const env,
    jobject,
    const jlong handle,
    jstring torrent_id_value,
    const jint file_index,
    jstring filename_hint_value
) {
    std::string torrent_id;
    std::string filename_hint;
    if (!read_utf8(env, torrent_id_value, torrent_id)) {
        return make_command_result(env, NUVIO_ENGINE_STATUS_INVALID_ARGUMENT, 0);
    }
    if (filename_hint_value != nullptr && !read_utf8(env, filename_hint_value, filename_hint)) {
        return make_command_result(env, NUVIO_ENGINE_STATUS_INVALID_ARGUMENT, 0);
    }
    nuvio_engine_stream_request request{};
    nuvio_engine_stream_request_init(&request);
    request.torrent_id = torrent_id.c_str();
    request.file_index = static_cast<std::uint32_t>(file_index);
    request.filename_hint = filename_hint_value == nullptr ? nullptr : filename_hint.c_str();
    std::uint64_t request_id = 0;
    const auto status = nuvio_engine_prepare_stream(
        engine_from_handle(handle),
        &request,
        &request_id
    );
    return make_command_result(env, status, request_id);
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_nuvio_engine_internal_NativeBridge_nativeStopStream(
    JNIEnv* const env,
    jobject,
    const jlong handle,
    jstring stream_id_value
) {
    std::string stream_id;
    if (!read_utf8(env, stream_id_value, stream_id)) {
        return make_command_result(env, NUVIO_ENGINE_STATUS_INVALID_ARGUMENT, 0);
    }
    std::uint64_t request_id = 0;
    const auto status = nuvio_engine_stop_stream(
        engine_from_handle(handle),
        stream_id.c_str(),
        &request_id
    );
    return make_command_result(env, status, request_id);
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_nuvio_engine_internal_NativeBridge_nativeRemoveTorrent(
    JNIEnv* const env,
    jobject,
    const jlong handle,
    jstring torrent_id_value
) {
    std::string torrent_id;
    if (!read_utf8(env, torrent_id_value, torrent_id)) {
        return make_command_result(env, NUVIO_ENGINE_STATUS_INVALID_ARGUMENT, 0);
    }
    std::uint64_t request_id = 0;
    const auto status = nuvio_engine_remove_torrent(
        engine_from_handle(handle),
        torrent_id.c_str(),
        &request_id
    );
    return make_command_result(env, status, request_id);
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_nuvio_engine_internal_NativeBridge_nativeGetStats(
    JNIEnv* const env,
    jobject,
    const jlong handle
) {
    nuvio_engine_stats stats{};
    nuvio_engine_stats_init(&stats);
    const auto status = nuvio_engine_get_stats(engine_from_handle(handle), &stats);
    const std::array<jlong, 53> values{
        static_cast<jlong>(status),
        static_cast<jlong>(stats.active_torrents),
        static_cast<jlong>(stats.active_streams),
        static_cast<jlong>(stats.active_http_requests),
        static_cast<jlong>(stats.connected_peers),
        static_cast<jlong>(stats.connected_seeds),
        static_cast<jlong>(stats.pending_piece_reads),
        static_cast<jlong>(stats.download_rate_bytes_per_second),
        static_cast<jlong>(stats.upload_rate_bytes_per_second),
        static_cast<jlong>(stats.total_payload_download_bytes),
        static_cast<jlong>(stats.total_payload_upload_bytes),
        static_cast<jlong>(stats.memory_cache_capacity_bytes),
        static_cast<jlong>(stats.memory_cache_used_bytes),
        static_cast<jlong>(stats.memory_cache_hits),
        static_cast<jlong>(stats.memory_cache_misses),
        static_cast<jlong>(stats.memory_cache_evictions),
        static_cast<jlong>(stats.memory_cache_entries),
        static_cast<jlong>(stats.warm_torrents),
        static_cast<jlong>(stats.quiesced_torrents),
        static_cast<jlong>(stats.disk_cache_capacity_bytes),
        static_cast<jlong>(stats.disk_cache_used_bytes),
        static_cast<jlong>(stats.disk_cache_protected_bytes),
        static_cast<jlong>(stats.disk_cache_evictions),
        static_cast<jlong>(stats.disk_cache_reclaimed_bytes),
        static_cast<jlong>(stats.disk_cache_over_budget != 0),
        static_cast<jlong>(stats.known_peers),
        static_cast<jlong>(stats.connect_candidates),
        static_cast<jlong>(stats.interested_peers),
        static_cast<jlong>(stats.unchoked_peers),
        static_cast<jlong>(stats.downloading_peers),
        static_cast<jlong>(stats.snubbed_peers),
        static_cast<jlong>(stats.pending_block_requests),
        static_cast<jlong>(stats.target_block_requests),
        static_cast<jlong>(stats.timed_out_block_requests),
        static_cast<jlong>(stats.connecting_peers),
        static_cast<jlong>(stats.handshaking_peers),
        static_cast<jlong>(stats.target_piece_peers),
        static_cast<jlong>(stats.target_piece_unchoked_peers),
        static_cast<jlong>(stats.target_piece_downloading_peers),
        static_cast<jlong>(stats.off_target_downloading_peers),
        static_cast<jlong>(stats.tracker_reply_events),
        static_cast<jlong>(stats.tracker_error_events),
        static_cast<jlong>(stats.dht_reply_events),
        static_cast<jlong>(stats.tracker_peers_returned),
        static_cast<jlong>(stats.dht_peers_returned),
        static_cast<jlong>(stats.peer_connect_events),
        static_cast<jlong>(stats.peer_disconnect_events),
        static_cast<jlong>(stats.peer_disconnect_timeouts),
        static_cast<jlong>(stats.peer_disconnect_connect_failures),
        static_cast<jlong>(stats.peer_disconnect_redundant),
        static_cast<jlong>(stats.peer_disconnect_turnover),
        static_cast<jlong>(stats.peer_disconnect_other),
        static_cast<jlong>(stats.torrent_finished_events),
    };
    const auto result = env->NewLongArray(static_cast<jsize>(values.size()));
    if (result != nullptr) {
        env->SetLongArrayRegion(result, 0, static_cast<jsize>(values.size()), values.data());
    }
    return result;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_nuvio_engine_internal_NativeBridge_nativeGetStreamStats(
    JNIEnv* const env,
    jobject,
    const jlong handle,
    jstring stream_id_value
) {
    std::string stream_id;
    nuvio_engine_stream_stats stats{};
    nuvio_engine_stream_stats_init(&stats);
    const auto status = read_utf8(env, stream_id_value, stream_id)
        ? nuvio_engine_get_stream_stats(
              engine_from_handle(handle),
              stream_id.c_str(),
              &stats
          )
        : static_cast<nuvio_engine_status>(NUVIO_ENGINE_STATUS_INVALID_ARGUMENT);
    const std::array<jlong, 17> values{
        static_cast<jlong>(status),
        static_cast<jlong>(stats.file_index),
        static_cast<jlong>(stats.file_size),
        static_cast<jlong>(stats.contiguous_ready_bytes),
        static_cast<jlong>(stats.verified_file_bytes),
        static_cast<jlong>(stats.delivered_bytes),
        static_cast<jlong>(stats.active_demands),
        static_cast<jlong>(stats.scheduled_pieces),
        static_cast<jlong>(stats.blocking_pieces),
        static_cast<jlong>(stats.primary_blocking_piece),
        static_cast<jlong>(stats.secondary_blocking_piece),
        static_cast<jlong>(stats.last_ready_piece),
        static_cast<jlong>(stats.primary_demand_start),
        static_cast<jlong>(stats.primary_demand_end),
        static_cast<jlong>(stats.secondary_demand_start),
        static_cast<jlong>(stats.secondary_demand_end),
        static_cast<jlong>(stats.schedule_revision),
    };
    const auto result = env->NewLongArray(static_cast<jsize>(values.size()));
    if (result != nullptr) {
        env->SetLongArrayRegion(result, 0, static_cast<jsize>(values.size()), values.data());
    }
    return result;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_nuvio_engine_internal_NativeBridge_nativeReclaimDiskCache(
    JNIEnv* const env,
    jobject,
    const jlong handle,
    const jlong target_bytes
) {
    std::uint64_t request_id = 0;
    const auto status = nuvio_engine_reclaim_disk_cache(
        engine_from_handle(handle),
        static_cast<std::uint64_t>(target_bytes),
        &request_id
    );
    return make_command_result(env, status, request_id);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_nuvio_engine_internal_NativeBridge_nativeStatusMessage(
    JNIEnv* const env,
    jobject,
    const jint status
) {
    return make_utf8(env, nuvio_engine_status_message(static_cast<nuvio_engine_status>(status)));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_nuvio_engine_internal_NativeBridge_nativeEngineVersion(
    JNIEnv* const env,
    jobject
) {
    return make_utf8(env, nuvio_engine_version_string());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_nuvio_engine_internal_NativeBridge_nativeBackendVersion(
    JNIEnv* const env,
    jobject
) {
    return make_utf8(env, nuvio_engine_protocol_backend_version());
}

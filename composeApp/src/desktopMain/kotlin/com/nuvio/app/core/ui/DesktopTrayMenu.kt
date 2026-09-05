package com.nuvio.app.core.ui

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.EventQueue
import java.awt.Font
import java.awt.Frame
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GraphicsDevice
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.event.ActionListener
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener
import java.awt.geom.RoundRectangle2D
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JPanel
import javax.swing.KeyStroke

/**
 * One line of the tray menu.
 *
 * [Info] is deliberately not clickable: it is there to say what this install is, and a row that
 * highlights under the pointer but does nothing when clicked reads as broken.
 */
internal sealed interface DesktopTrayMenuEntry {
    data class Action(val label: String, val onSelected: () -> Unit) : DesktopTrayMenuEntry
    data class Info(val label: String) : DesktopTrayMenuEntry
    object Separator : DesktopTrayMenuEntry
}

private const val MenuCornerRadius = 12
private const val MenuShadowMargin = 12
private const val MenuContentPaddingVertical = 6
private const val MenuItemHeight = 34
private const val MenuItemHorizontalPadding = 14
private const val MenuItemCornerRadius = 8
private const val MenuSeparatorHeight = 9
private const val MenuMinContentWidth = 190
private const val MenuMaxContentWidth = 340
private const val MenuCursorGap = 6

// Tracks the app's own elevated surface (ThemeColors.backgroundElevated) rather than the Win32
// menu grey, so the tray reads as part of Nuvio.
private val MenuBackground = Color(0x1A, 0x1A, 0x1A)
private val MenuBorderColor = Color(0xFF, 0xFF, 0xFF, 26)
private val MenuSeparatorColor = Color(0xFF, 0xFF, 0xFF, 20)
private val MenuLabelColor = Color(0xE8, 0xE8, 0xE8)
private val MenuSecondaryLabelColor = Color(0x8C, 0x8C, 0x8C)
private val MenuHoverColor = Color(0xFF, 0xFF, 0xFF, 22)

/**
 * The dark, rounded menu shown when the tray icon is right-clicked.
 *
 * `java.awt.PopupMenu` — the only menu a `TrayIcon` can host natively — is a raw Win32 menu: grey
 * background, system highlight blue, no rounding and no way to restyle any of it. This paints the
 * menu itself in an undecorated always-on-top dialog instead.
 *
 * A JDialog rather than a JWindow on purpose: a Window that is neither a Frame nor a Dialog can
 * only take focus while it has a showing focusable owner, and there is no app window showing when
 * Nuvio is closed to the tray. With no focus there is nothing to lose, so the menu would never
 * dismiss itself.
 */
internal object DesktopTrayMenu {
    private var dialog: JDialog? = null

    /** Shows the menu near ([screenX], [screenY]), replacing any menu already open. */
    fun show(entries: List<DesktopTrayMenuEntry>, screenX: Int, screenY: Int) {
        EventQueue.invokeLater {
            dismiss()
            if (entries.isEmpty()) return@invokeLater

            val translucent = runCatching {
                GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .defaultScreenDevice
                    .isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT)
            }.getOrDefault(false)
            val menu = JDialog(null as Frame?, false).apply {
                isUndecorated = true
                isAlwaysOnTop = true
                focusableWindowState = true
                isAutoRequestFocus = true
            }
            menu.contentPane = TrayMenuPanel(
                entries = entries,
                drawShadow = translucent,
                onSelected = { action ->
                    dismiss()
                    EventQueue.invokeLater(action)
                },
                onDismissRequest = { dismiss() },
            )
            menu.background = if (translucent) Color(0, 0, 0, 0) else MenuBackground
            menu.pack()
            if (!translucent) {
                // No per-pixel alpha to carve the corners out of, so clip the window itself.
                menu.shape = RoundRectangle2D.Float(
                    0f,
                    0f,
                    menu.width.toFloat(),
                    menu.height.toFloat(),
                    MenuCornerRadius * 2f,
                    MenuCornerRadius * 2f,
                )
            }
            menu.location = menuLocation(
                size = menu.size,
                screenX = screenX,
                screenY = screenY,
                shadow = if (translucent) MenuShadowMargin else 0,
            )
            menu.rootPane.registerKeyboardAction(
                ActionListener { dismiss() },
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW,
            )
            menu.addWindowFocusListener(object : WindowFocusListener {
                override fun windowGainedFocus(e: WindowEvent?) = Unit
                override fun windowLostFocus(e: WindowEvent?) = dismiss()
            })
            dialog = menu
            menu.isVisible = true
            menu.toFront()
            menu.requestFocus()
        }
    }

    fun dismiss() {
        val open = dialog ?: return
        dialog = null
        open.isVisible = false
        open.dispose()
    }
}

/**
 * Places the menu against the cursor, flipping it to whichever side of the pointer has room. The
 * tray lives in a screen corner, so the naive "below and to the right" placement is the one
 * position that is always wrong.
 */
private fun menuLocation(size: Dimension, screenX: Int, screenY: Int, shadow: Int): Point {
    val screen = usableScreenBounds(screenX, screenY)
    val gap = MenuCursorGap - shadow
    val y = if (screenY > screen.y + screen.height / 2) screenY - size.height - gap else screenY + gap
    val x = screenX - size.width / 2
    val maxX = (screen.x + screen.width - size.width + shadow).coerceAtLeast(screen.x - shadow)
    val maxY = (screen.y + screen.height - size.height + shadow).coerceAtLeast(screen.y - shadow)
    return Point(x.coerceIn(screen.x - shadow, maxX), y.coerceIn(screen.y - shadow, maxY))
}

/** The bounds of the screen holding the cursor, minus the taskbar. */
private fun usableScreenBounds(screenX: Int, screenY: Int): Rectangle {
    val environment = GraphicsEnvironment.getLocalGraphicsEnvironment()
    val configuration = environment.screenDevices
        .map { it.defaultConfiguration }
        .firstOrNull { it.bounds.contains(screenX, screenY) }
        ?: environment.defaultScreenDevice.defaultConfiguration
    val bounds = Rectangle(configuration.bounds)
    val insets = runCatching { Toolkit.getDefaultToolkit().getScreenInsets(configuration) }.getOrNull()
    if (insets != null) {
        bounds.x += insets.left
        bounds.y += insets.top
        bounds.width -= insets.left + insets.right
        bounds.height -= insets.top + insets.bottom
    }
    return bounds
}

private fun menuFont(size: Int): Font {
    val segoe = Font("Segoe UI", Font.PLAIN, size)
    val family = if (segoe.family.equals("Segoe UI", ignoreCase = true)) "Segoe UI" else Font.SANS_SERIF
    return Font(family, Font.PLAIN, size)
}

/** Internal rather than private so a render test can paint it without opening a window. */
internal class TrayMenuPanel(
    entries: List<DesktopTrayMenuEntry>,
    private val drawShadow: Boolean,
    onSelected: (() -> Unit) -> Unit,
    onDismissRequest: () -> Unit = {},
) : JPanel() {
    private val inset = if (drawShadow) MenuShadowMargin else 0

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        // A translucent window still swallows clicks in its transparent parts, so the shadow margin
        // would otherwise be a dead border that neither acts nor closes.
        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                val body = Rectangle(inset, inset, width - inset * 2, height - inset * 2)
                if (!body.contains(e.point)) onDismissRequest()
            }
        })
        border = BorderFactory.createEmptyBorder(
            inset + MenuContentPaddingVertical,
            inset,
            inset + MenuContentPaddingVertical,
            inset,
        )
        val font = menuFont(13)
        val metrics = getFontMetrics(font)
        val widest = entries.mapNotNull { entry ->
            when (entry) {
                is DesktopTrayMenuEntry.Action -> entry.label
                is DesktopTrayMenuEntry.Info -> entry.label
                DesktopTrayMenuEntry.Separator -> null
            }
        }.maxOfOrNull { metrics.stringWidth(it) } ?: 0
        val contentWidth = (widest + MenuItemHorizontalPadding * 2)
            .coerceIn(MenuMinContentWidth, MenuMaxContentWidth)

        entries.forEach { entry ->
            val component: JComponent = when (entry) {
                is DesktopTrayMenuEntry.Action -> TrayMenuItem(
                    label = entry.label,
                    font = font,
                    color = MenuLabelColor,
                    onClick = { onSelected(entry.onSelected) },
                )
                is DesktopTrayMenuEntry.Info -> TrayMenuItem(
                    label = entry.label,
                    font = font,
                    color = MenuSecondaryLabelColor,
                    onClick = null,
                )
                DesktopTrayMenuEntry.Separator -> TrayMenuSeparator()
            }
            val size = Dimension(contentWidth, component.preferredSize.height)
            component.preferredSize = size
            component.maximumSize = size
            component.minimumSize = size
            add(component)
        }
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            if (drawShadow) {
                // Concentric rounded rects at a low alpha each: cheap, and soft enough that the
                // menu sits on the desktop instead of being pasted onto it.
                for (step in inset downTo 1) {
                    g2.color = Color(0, 0, 0, 8)
                    g2.fillRoundRect(
                        inset - step,
                        inset - step + 2,
                        width - (inset - step) * 2,
                        height - (inset - step) * 2,
                        MenuCornerRadius * 2 + step,
                        MenuCornerRadius * 2 + step,
                    )
                }
            }
            val w = width - inset * 2
            val h = height - inset * 2
            g2.color = MenuBackground
            g2.fillRoundRect(inset, inset, w, h, MenuCornerRadius * 2, MenuCornerRadius * 2)
            g2.color = MenuBorderColor
            g2.stroke = BasicStroke(1f)
            g2.drawRoundRect(inset, inset, w - 1, h - 1, MenuCornerRadius * 2, MenuCornerRadius * 2)
        } finally {
            g2.dispose()
        }
    }
}

private class TrayMenuItem(
    private val label: String,
    private val font: Font,
    private val color: Color,
    private val onClick: (() -> Unit)?,
) : JComponent() {
    private var hovered = false

    init {
        preferredSize = Dimension(MenuMinContentWidth, MenuItemHeight)
        isOpaque = false
        if (onClick != null) {
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) {
                    hovered = true
                    repaint()
                }

                override fun mouseExited(e: MouseEvent) {
                    hovered = false
                    repaint()
                }

                override fun mouseReleased(e: MouseEvent) {
                    if (contains(e.point)) onClick.invoke()
                }
            })
        }
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            if (hovered) {
                g2.color = MenuHoverColor
                g2.fillRoundRect(4, 1, width - 8, height - 2, MenuItemCornerRadius * 2, MenuItemCornerRadius * 2)
            }
            g2.font = font
            g2.color = color
            val metrics = g2.fontMetrics
            val baseline = (height - metrics.height) / 2 + metrics.ascent
            g2.drawString(label, MenuItemHorizontalPadding, baseline)
        } finally {
            g2.dispose()
        }
    }
}

private class TrayMenuSeparator : JComponent() {
    init {
        preferredSize = Dimension(MenuMinContentWidth, MenuSeparatorHeight)
        isOpaque = false
    }

    override fun paintComponent(g: Graphics) {
        g.color = MenuSeparatorColor
        g.fillRect(0, height / 2, width, 1)
    }
}

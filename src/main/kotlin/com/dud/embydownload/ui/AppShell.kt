package com.dud.embydownload.ui

import com.dud.embydownload.EmbyApplication
import com.dud.embydownload.settings.AppSettings
import javafx.css.PseudoClass
import javafx.fxml.FXMLLoader
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.geometry.Rectangle2D
import javafx.scene.Cursor
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.image.Image
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.StackPane
import javafx.scene.paint.Color
import javafx.scene.shape.Rectangle
import javafx.stage.Screen
import javafx.stage.Stage
import javafx.stage.StageStyle

/**
 * 无边框 Material 风格窗口壳：顶部自定义标题栏（拖动 / 最小化 / 最大化 / 关闭）。
 */
object AppShell {
    private val maximizedPseudo = PseudoClass.getPseudoClass("maximized")
    private const val DARK_STYLESHEET = "styles-dark.css"
    private const val CORNER_RADIUS = 12.0

    private lateinit var stage: Stage
    private lateinit var root: BorderPane
    private lateinit var contentHost: StackPane
    private lateinit var titleLabel: Label
    private lateinit var maximizeButton: Button
    private lateinit var sceneRef: Scene
    private lateinit var shellClip: Rectangle

    private var xOffset = 0.0
    private var yOffset = 0.0
    private const val RESIZE_MARGIN = 6.0

    private var snappedToWorkArea = false
    private var restoreBounds: Rectangle2D? = null

    private data class NavEntry(
        val fxml: String,
        val title: String,
        val configure: ((Any) -> Unit)?
    )

    private val history = ArrayDeque<NavEntry>()
    private var currentEntry: NavEntry? = null
    private var suppressHistory = false

    fun init(primaryStage: Stage) {
        stage = primaryStage
        stage.initStyle(StageStyle.TRANSPARENT)
        stage.minWidth = 880.0
        stage.minHeight = 600.0
        applyAppIcons(stage)

        titleLabel = Label("Emby Download").apply {
            styleClass += "window-title"
        }

        val dragArea = HBox(10.0).apply {
            alignment = Pos.CENTER_LEFT
            children += Label("ED").apply { styleClass += "window-logo" }
            children += titleLabel
            HBox.setHgrow(this, Priority.ALWAYS)
            setOnMousePressed { event ->
                if (!isExpanded) {
                    xOffset = event.sceneX
                    yOffset = event.sceneY
                }
            }
            setOnMouseDragged { event ->
                if (!isExpanded) {
                    stage.x = event.screenX - xOffset
                    stage.y = event.screenY - yOffset
                }
            }
            setOnMouseClicked { event ->
                if (event.clickCount == 2) {
                    toggleMaximize()
                }
            }
        }

        val minimizeButton = windowButton("─", "window-btn").apply {
            setOnAction { stage.isIconified = true }
        }
        maximizeButton = windowButton("□", "window-btn").apply {
            setOnAction { toggleMaximize() }
        }
        val closeButton = windowButton("✕", "window-btn", "window-btn-close").apply {
            setOnAction { stage.close() }
        }

        val controls = HBox(4.0, minimizeButton, maximizeButton, closeButton).apply {
            alignment = Pos.CENTER_RIGHT
        }

        val titleBar = HBox(12.0, dragArea, controls).apply {
            alignment = Pos.CENTER_LEFT
            padding = Insets(0.0, 8.0, 0.0, 16.0)
            prefHeight = 44.0
            minHeight = 44.0
            styleClass += "title-bar"
        }

        contentHost = StackPane().apply {
            styleClass += "content-host"
        }

        root = BorderPane().apply {
            styleClass += "app-shell"
            top = titleBar
            center = contentHost
        }

        shellClip = Rectangle().apply {
            arcWidth = CORNER_RADIUS * 2
            arcHeight = CORNER_RADIUS * 2
        }
        root.clip = shellClip
        root.layoutBoundsProperty().addListener { _, _, bounds ->
            shellClip.width = bounds.width
            shellClip.height = bounds.height
        }

        sceneRef = Scene(root, 980.0, 680.0).apply {
            fill = Color.TRANSPARENT
            stylesheets += EmbyApplication::class.java.getResource("styles.css")!!.toExternalForm()
        }
        enableResize(sceneRef)

        stage.scene = sceneRef
        applyTheme(AppSettings.darkMode)
    }

    private val isExpanded: Boolean
        get() = snappedToWorkArea

    fun getStage(): Stage = stage

    fun isDarkMode(): Boolean = AppSettings.darkMode

    fun applyTheme(dark: Boolean) {
        AppSettings.darkMode = dark
        val darkUrl = EmbyApplication::class.java.getResource(DARK_STYLESHEET)!!.toExternalForm()
        sceneRef.stylesheets.removeIf { it.endsWith(DARK_STYLESHEET) }
        root.styleClass.removeAll("theme-dark")
        if (dark) {
            sceneRef.stylesheets += darkUrl
            root.styleClass.add("theme-dark")
        }
    }

    fun show() {
        stage.show()
    }

    fun setTitle(title: String) {
        titleLabel.text = title
        stage.title = title
    }

    fun clearNavigationHistory() {
        history.clear()
        currentEntry = null
    }

    fun canGoBack(): Boolean = history.isNotEmpty()

    fun goBack(): Boolean {
        val previous = history.removeLastOrNull() ?: return false
        suppressHistory = true
        try {
            openEntry<Any>(previous)
        } finally {
            suppressHistory = false
        }
        return true
    }

    fun <T> navigate(
        fxml: String,
        title: String,
        pushHistory: Boolean = true,
        configure: ((T) -> Unit)? = null
    ): T {
        if (pushHistory && !suppressHistory) {
            currentEntry?.let { history.addLast(it) }
        }
        val entry = NavEntry(fxml, title) { ctrl ->
            @Suppress("UNCHECKED_CAST")
            configure?.invoke(ctrl as T)
        }
        return openEntry(entry)
    }

    fun <T> navigateReplace(fxml: String, title: String, configure: ((T) -> Unit)? = null): T {
        history.clear()
        return navigate(fxml, title, pushHistory = false, configure = configure)
    }

    private fun <T> openEntry(entry: NavEntry): T {
        currentEntry = entry
        val loader = FXMLLoader(EmbyApplication::class.java.getResource(entry.fxml))
        val page = loader.load<Parent>()
        val controller = loader.getController<T>()
        entry.configure?.invoke(controller as Any)
        contentHost.children.setAll(page)
        setTitle(entry.title)
        return controller
    }

    private fun toggleMaximize() {
        if (snappedToWorkArea) {
            restoreFromWorkArea()
        } else {
            expandToWorkArea()
        }
    }

    private fun expandToWorkArea() {
        val bounds = Screen.getPrimary().visualBounds
        restoreBounds = Rectangle2D(stage.x, stage.y, stage.width, stage.height)
        stage.x = bounds.minX
        stage.y = bounds.minY
        stage.width = bounds.width
        stage.height = bounds.height
        snappedToWorkArea = true
        maximizeButton.text = "❐"
        root.pseudoClassStateChanged(maximizedPseudo, true)
        shellClip.arcWidth = 0.0
        shellClip.arcHeight = 0.0
    }

    private fun restoreFromWorkArea() {
        val bounds = restoreBounds
        if (bounds != null) {
            stage.x = bounds.minX
            stage.y = bounds.minY
            stage.width = bounds.width
            stage.height = bounds.height
        }
        restoreBounds = null
        snappedToWorkArea = false
        maximizeButton.text = "□"
        root.pseudoClassStateChanged(maximizedPseudo, false)
        shellClip.arcWidth = CORNER_RADIUS * 2
        shellClip.arcHeight = CORNER_RADIUS * 2
    }

    fun applyAppIcons(target: Stage) {
        if (target.icons.isNotEmpty()) return
        val iconUrl = EmbyApplication::class.java.getResource("icons/app-icon.png") ?: return
        target.icons += Image(iconUrl.toExternalForm())
    }

    private fun windowButton(text: String, vararg classes: String): Button =
        Button(text).apply {
            styleClass.addAll(classes)
            isFocusTraversable = false
            prefWidth = 42.0
            prefHeight = 32.0
            minWidth = 42.0
            minHeight = 32.0
        }

    private fun enableResize(scene: Scene) {
        scene.setOnMouseMoved { event ->
            if (isExpanded) {
                scene.cursor = Cursor.DEFAULT
                return@setOnMouseMoved
            }
            scene.cursor = resizeCursor(event.x, event.y, scene.width, scene.height)
        }

        scene.setOnMouseDragged { event ->
            if (isExpanded) return@setOnMouseDragged
            val cursor = scene.cursor
            val mouseX = event.screenX
            val mouseY = event.screenY

            when (cursor) {
                Cursor.NW_RESIZE -> {
                    resizeFromLeft(mouseX)
                    resizeFromTop(mouseY)
                }
                Cursor.NE_RESIZE -> {
                    resizeFromRight(mouseX)
                    resizeFromTop(mouseY)
                }
                Cursor.SW_RESIZE -> {
                    resizeFromLeft(mouseX)
                    resizeFromBottom(mouseY)
                }
                Cursor.SE_RESIZE -> {
                    resizeFromRight(mouseX)
                    resizeFromBottom(mouseY)
                }
                Cursor.E_RESIZE -> resizeFromRight(mouseX)
                Cursor.W_RESIZE -> resizeFromLeft(mouseX)
                Cursor.N_RESIZE -> resizeFromTop(mouseY)
                Cursor.S_RESIZE -> resizeFromBottom(mouseY)
            }
        }
    }

    private fun resizeCursor(x: Double, y: Double, width: Double, height: Double): Cursor {
        val left = x < RESIZE_MARGIN
        val right = x > width - RESIZE_MARGIN
        val top = y < RESIZE_MARGIN
        val bottom = y > height - RESIZE_MARGIN
        return when {
            left && top -> Cursor.NW_RESIZE
            right && top -> Cursor.NE_RESIZE
            left && bottom -> Cursor.SW_RESIZE
            right && bottom -> Cursor.SE_RESIZE
            left -> Cursor.W_RESIZE
            right -> Cursor.E_RESIZE
            top -> Cursor.N_RESIZE
            bottom -> Cursor.S_RESIZE
            else -> Cursor.DEFAULT
        }
    }

    private fun resizeFromLeft(mouseX: Double) {
        val newWidth = stage.width + stage.x - mouseX
        if (newWidth >= stage.minWidth) {
            stage.width = newWidth
            stage.x = mouseX
        }
    }

    private fun resizeFromRight(mouseX: Double) {
        val newWidth = mouseX - stage.x
        if (newWidth >= stage.minWidth) {
            stage.width = newWidth
        }
    }

    private fun resizeFromTop(mouseY: Double) {
        val newHeight = stage.height + stage.y - mouseY
        if (newHeight >= stage.minHeight) {
            stage.height = newHeight
            stage.y = mouseY
        }
    }

    private fun resizeFromBottom(mouseY: Double) {
        val newHeight = mouseY - stage.y
        if (newHeight >= stage.minHeight) {
            stage.height = newHeight
        }
    }
}

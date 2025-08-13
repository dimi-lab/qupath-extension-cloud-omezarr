package dimilab.qupath.ext.omezarr

import javafx.beans.property.BooleanProperty
import javafx.beans.property.Property
import javafx.event.ActionEvent
import javafx.event.EventHandler
import javafx.scene.control.Alert
import javafx.scene.control.ButtonType
import javafx.scene.control.MenuItem
import org.apache.commons.io.FileUtils
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import qupath.fx.prefs.controlsfx.PropertyItemBuilder
import qupath.lib.common.Version
import qupath.lib.gui.QuPathGUI
import qupath.lib.gui.extensions.GitHubProject
import qupath.lib.gui.extensions.GitHubProject.GitHubRepo
import qupath.lib.gui.extensions.QuPathExtension
import qupath.lib.gui.prefs.PathPrefs
import qupath.lib.io.PathIO
import java.nio.file.Files
import java.util.*
import java.util.function.Consumer
import java.util.function.Predicate


class CloudOmeZarrExtension : QuPathExtension, GitHubProject {

  companion object {
    val logger: Logger = LoggerFactory.getLogger(CloudOmeZarrExtension::class.java)

    val resources: ResourceBundle = ResourceBundle.getBundle("dimilab.qupath.ext.omezarr.ui.strings")

    // Display name
    val EXTENSION_NAME: String = resources.getString("name")

    // Short description
    val EXTENSION_DESCRIPTION: String = resources.getString("description")

    // Expected QuPath version
    val EXTENSION_QUPATH_VERSION: Version = Version.parse("v0.5.0")

    // GitHub repo for updates
    val EXTENSION_REPOSITORY: GitHubRepo = GitHubRepo.create(
      EXTENSION_NAME, "dimi-lab", "qupath-extension-cloud-omezarr"
    )

    /**
     * A 'persistent preference' - showing how to create a property that is stored whenever QuPath is closed.
     * This preference will be managed in the main QuPath GUI preferences window.
     */
    val enableExtensionProperty: BooleanProperty = PathPrefs.createPersistentPreference(
      "enableExtension", true
    )

    /**
     * Another 'persistent preference'.
     * This one will be managed using a GUI element created by the extension.
     * We use [<] rather than [IntegerProperty]
     * because of the type of GUI element we use to manage it.
     */
    val integerOption: Property<Int> = PathPrefs.createPersistentPreference(
      "demo.num.option", 1
    ).asObject()
  }

  /**
   * Flag whether the extension is already installed (might not be needed... but we'll do it anyway)
   */
  private var isInstalled = false

  /**
   * An example of how to expose persistent preferences to other classes in your extension.
   * @return The persistent preference, so that it can be read or set somewhere else.
   */
  fun integerOptionProperty(): Property<Int> {
    return integerOption
  }

  override fun installExtension(qupath: QuPathGUI) {
    if (isInstalled) {
      logger.debug("{} is already installed", name)
      return
    }
    isInstalled = true
    addPreferenceToPane(qupath)
    addMenuItem(qupath)
  }

  /**
   * Demo showing how to add a persistent preference to the QuPath preferences pane.
   * The preference will be in a section of the preference pane based on the
   * category you set. The description is used as a tooltip.
   * @param qupath The currently running QuPathGUI instance.
   */
  private fun addPreferenceToPane(qupath: QuPathGUI) {
    val propertyItem = PropertyItemBuilder(enableExtensionProperty, Boolean::class.java)
      .name(resources.getString("menu.enable"))
      .category("Demo extension")
      .description("Enable the demo extension")
      .build()
    qupath.preferencePane
      .propertySheet
      .items
      .add(propertyItem)
  }


  /**
   * Demo showing how a new command can be added to a QuPath menu.
   * @param qupath The QuPath GUI
   */
  private fun addMenuItem(qupath: QuPathGUI) {
    val menu = qupath.getMenu("Extensions>$EXTENSION_NAME", true)

    val refreshPathObjectsMenuItem = MenuItem("Refresh remote PathObjects")
    refreshPathObjectsMenuItem.onAction = EventHandler { _: ActionEvent? -> createRefreshRemotePathObjectsStage() }
    refreshPathObjectsMenuItem.disableProperty().bind(enableExtensionProperty.not())
    menu.items.add(refreshPathObjectsMenuItem)

    val saveToRemoteMenuItem = MenuItem("Save image data to remote")
    saveToRemoteMenuItem.onAction = EventHandler { _: ActionEvent? -> createSaveToRemoteStage() }
    saveToRemoteMenuItem.disableProperty().bind(enableExtensionProperty.not())
    menu.items.add(saveToRemoteMenuItem)
  }

  /**
   * Demo showing how to create a new stage with a JavaFX FXML interface.
   */
  private fun createRefreshRemotePathObjectsStage() {
    val dialog = Alert(
      Alert.AlertType.CONFIRMATION,
      "Delete all local path objects and refresh from server?"
    )
    dialog.showAndWait()
      .filter(Predicate { response: ButtonType? -> response == ButtonType.OK })
      .ifPresent(Consumer { _: ButtonType? -> doRefreshRemotePathObjects() })
  }

  private fun doRefreshRemotePathObjects() {
    val imageData = QuPathGUI.getInstance().imageData
    val server = imageData.server
    if (server !is CloudOmeZarrServer) {
      logger.error("Image server is not a CloudOmeZarrServer")
      return
    }

    // TODO: move this to a background thread, and add a spinner?

    logger.info("Refreshing remote path objects from server: $server; ${server.getImageArgs().remoteQpDataPath}")
    imageData.hierarchy.clearAll()
    imageData.hierarchy.addObjects(server.readPathObjects())
    logger.info("Path objects refreshed")
  }

  private fun createSaveToRemoteStage() {
    val dialog = Alert(
      Alert.AlertType.CONFIRMATION,
      "Save image data to remote storage?"
    )
    dialog.showAndWait()
      .filter(Predicate { response: ButtonType? -> response == ButtonType.OK })
      .ifPresent(Consumer { _: ButtonType? -> doSaveToRemote() })
  }

  private fun doSaveToRemote() {
    val imageData = QuPathGUI.getInstance().imageData
    val server = imageData.server
    if (server !is CloudOmeZarrServer) {
      logger.error("Image server is not a CloudOmeZarrServer")
      return
    }
    val remotePath = server.getImageArgs().remoteQpDataPath
    if (remotePath == null) {
      logger.error("Remote qpdata path is null")
      return
    }

    val localFile = Files.createTempFile("qupath-cloudomezarr", ".qpdata")
    Runtime.getRuntime().addShutdownHook(Thread { FileUtils.forceDelete(localFile.toFile()) })

    // TODO: move this to a background thread, and add a spinner?

    logger.info("Writing image data to temp file: $localFile")
    PathIO.writeImageData(localFile, imageData)

    logger.info("Uploading image data to: $remotePath")
    val time = System.currentTimeMillis()
    uploadToStorage(localFile, remotePath.toBlobId())
    logger.info("Image data uploaded in ${System.currentTimeMillis() - time} ms")
  }

  override fun getName(): String {
    return EXTENSION_NAME
  }

  override fun getDescription(): String {
    return EXTENSION_DESCRIPTION
  }

  override fun getQuPathVersion(): Version {
    return EXTENSION_QUPATH_VERSION
  }

  override fun getRepository(): GitHubRepo {
    return EXTENSION_REPOSITORY
  }
}

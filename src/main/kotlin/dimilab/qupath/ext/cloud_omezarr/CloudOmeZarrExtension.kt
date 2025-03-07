package dimilab.qupath.ext.cloud_omezarr

import dimilab.qupath.ext.cloud_omezarr.ui.InterfaceController
import javafx.beans.property.BooleanProperty
import javafx.beans.property.Property
import javafx.event.ActionEvent
import javafx.event.EventHandler
import javafx.scene.Scene
import javafx.scene.control.MenuItem
import javafx.stage.Stage
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import qupath.fx.dialogs.Dialogs
import qupath.fx.prefs.controlsfx.PropertyItemBuilder
import qupath.lib.common.Version
import qupath.lib.gui.QuPathGUI
import qupath.lib.gui.extensions.GitHubProject
import qupath.lib.gui.extensions.GitHubProject.GitHubRepo
import qupath.lib.gui.extensions.QuPathExtension
import qupath.lib.gui.prefs.PathPrefs
import java.io.IOException
import java.util.*

class CloudOmeZarrExtension : QuPathExtension, GitHubProject {

  companion object {
    val logger: Logger = LoggerFactory.getLogger(CloudOmeZarrExtension::class.java)

    val resources: ResourceBundle = ResourceBundle.getBundle("dimilab.qupath.ext.cloud_omezarr.ui.strings")
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

  /**
   * Create a stage for the extension to display
   */
  private var stage: Stage? = null

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
    val menuItem = MenuItem("OME-Zarr menu item")
    menuItem.onAction = EventHandler { _: ActionEvent? -> createStage() }
    menuItem.disableProperty().bind(enableExtensionProperty.not())
    menu.items.add(menuItem)
  }

  /**
   * Demo showing how to create a new stage with a JavaFX FXML interface.
   */
  private fun createStage() {
    if (stage == null) {
      try {
        stage = Stage()
        val scene = Scene(InterfaceController.createInstance())
        stage!!.initOwner(QuPathGUI.getInstance().stage)
        stage!!.title = resources.getString("stage.title")
        stage!!.scene = scene
        stage!!.isResizable = false
      } catch (e: IOException) {
        Dialogs.showErrorMessage(resources.getString("error"), resources.getString("error.gui-loading-failed"))
        logger.error("Unable to load extension interface FXML", e)
      }
    }
    stage!!.show()
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
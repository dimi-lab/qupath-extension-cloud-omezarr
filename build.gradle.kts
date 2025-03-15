plugins {
  // To optionally create a shadow/fat jar that bundle up any non-core dependencies
  id("com.gradleup.shadow") version "8.3.5"
  // QuPath Gradle extension convention plugin
  id("qupath-conventions")

  kotlin("jvm") version "2.1.10"
}

repositories {
  mavenCentral()

  maven {
    name = "Maven SciJava"
    url = uri("https://maven.scijava.org/content/groups/public")
  }
}

qupathExtension {
  name = "qupath-extension-cloud-omezarr"
  group = "io.github.dimilab"
  version = "0.1.0-SNAPSHOT"
  description = "QuPath extension to load OME-Zarr images from cloud storage"
  automaticModule = "io.github.dimi-lab.qupath.extension.cloud-omezarr"
}

tasks.named("build") {
  dependsOn("shadowJar")
}

kotlin {
  jvmToolchain(17)
}

dependencies {
  implementation(kotlin("stdlib-jdk8"))

  implementation("net.java.dev.jna:jna:5.16.0")

  // Main dependencies for most QuPath extensions
  shadow(libs.bundles.qupath)
  shadow(libs.bundles.logging)
  shadow(libs.qupath.fxtras)

  // OME + Zarr
  implementation("ome:formats-api:8.1.0")
  implementation("ome:formats-common:5.2.4")
  implementation("org.openmicroscopy:ome-model:6.4.0")
  implementation("dev.zarr:jzarr:0.4.2")

  // For testing
  testImplementation(libs.bundles.qupath)
  testImplementation(kotlin("test"))
}

repositories {
  mavenCentral()
}

tasks.test {
  useJUnitPlatform()
}

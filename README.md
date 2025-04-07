# qupath-ext-cloud-omezarr
QuPath extension to load OME-Zarr images from cloud storage

## Demo

The extension is currently capable of reading an OME-Zarr file (as output by bioformats2raw spec version 0.4). The input file is LuCa7 [described here](src/test/resources/test.zarr/README.md).

![Loading an OME-Zarr image](images/luca7-omezarr.png)

## Warning: experimental extension

⚠️ Use this extension at your own risk ⚠️

Its API & behavior are likely to change.

## Why an extension?

Our data is cloud-based, and traditional file system bridges (like cloud NFS) are non-performant or very expensive. Cloud viewers exist (like [Avivator](https://avivator.gehlenborglab.org/), part of [Viv](https://github.com/hms-dbmi/viv)) but are view-only: no support for annotations.

The OME-NGFF aka OME-Zarr format is a natural choice for scalable cloud access. Clients can download just the chunks they need, at the level of detail (aka resolution / downsampling) they need.

Our primary user profile is a bioinformatician who is very familiar with QuPath, and does most of their work using QuPath (tools, scripting, etc.). It would be a lot of work to develop & maintain a QuPath replacement, with no benefit other than cloud compatibility.

QuPath has a rich extension framework; for example the [OMERO integration](https://github.com/qupath/qupath-extension-omero-web/) is an extension as is the support for the [BioFormats plugin](https://github.com/petebankhead/qupath/blob/acdbce962813b9edfd72e3d0384b4213d8ee89ce/qupath-extension-bioformats/src/main/java/qupath/lib/images/servers/bioformats/BioFormatsImageServer.java).

The [ZarrReader BioFormats extension](https://github.com/ome/ZarrReader) support OME-Zarr, but is S3 only, and has a longer release cycle. Furthermore, we don't need the full BioFormats complexity, at least not yet while we prove out cloud annotations. As an example of the complexity going between BioFormats and QuPath, see this [PR addressing optimal tile size](https://github.com/qupath/qupath/pull/1645).

As we build & test our cloud-first solution, we need:

1. a simple release cycle
2. direct, low(-ish) level access to the data

A QuPath extension seems like the perfect fit.

## Build instructions

You need [c-blosc](https://github.com/Blosc/c-blosc) to run the test suite.

Platform instructions:

* On Mac, `brew install c-blosc`, then record the results of: `echo $(brew --cellar c-blosc)/*/lib/`
* [untested] On Ubuntu, `apt-get install libblosc1`, and that's it?
* [untested] On Windows, download from the [Fiji project](https://sites.imagej.net/N5/lib/win64/), and take note of where you placed it

Set `CBLOSC_LIB` to the location containing the library.

## Runtime instructions

To load images with the c-blosc compression algorithm, you need to configure QuPath with the library location as above. See [QuPath setup instructions](https://github.com/qupath/qupath/wiki/Working-with-Python#setup) for Java Embedded Python, you need to edit the config or launch with a flag as specified.


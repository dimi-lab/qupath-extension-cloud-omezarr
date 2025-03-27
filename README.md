# qupath-ext-cloud-omezarr
QuPath extension to load OME-Zarr images from cloud storage

## Demo

The extension is currently capable of reading an OME-Zarr file (as output by bioformats2raw spec version 0.4). The input file is LuCa7 [described here](src/test/resources/test.zarr/README.md).

![Loading an OME-Zarr image](images/luca7-omezarr.png)

## Warning: experimental extension

⚠️ Use this extension at your own risk ⚠️

Its API & behavior are likely to change.

## Build instructions

You need [c-blosc](https://github.com/Blosc/c-blosc) to run the test suite.

Platform instructions:

* On Mac, `brew install c-blosc`, then record the results of: `echo $(brew --cellar c-blosc)/*/lib/`
* [untested] On Ubuntu, `apt-get install libblosc1`, and that's it?
* [untested] On Windows, download from the [Fiji project](https://sites.imagej.net/N5/lib/win64/), and take note of where you placed it

Set `CBLOSC_LIB` to the location containing the library.

## Runtime instructions

To load images with the c-blosc compression algorithm, you need to configure QuPath with the library location as above. See [QuPath setup instructions](https://github.com/qupath/qupath/wiki/Working-with-Python#setup) for Java Embedded Python, you need to edit the config or launch with a flag as specified.


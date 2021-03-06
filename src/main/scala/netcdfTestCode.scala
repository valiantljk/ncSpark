package org.apache.spark.mllib.linalg.distributed

// basic Spark
import org.apache.spark.SparkContext
import org.apache.spark.SparkConf
import org.apache.spark.Logging
import scala.util.Random
import scala.reflect.runtime.universe._
// Breeze
import breeze.linalg.{DenseVector, DenseMatrix}

// Mllib support

// support my primitive logging routines
import java.util.Calendar
import java.text.SimpleDateFormat

// NetCDF support
import ucar.nc2.{NetcdfFile, NetcdfFileWriter, Dimension, Variable}
import ucar.ma2.DataType
import ucar.ma2.{Array => netcdfArray}

// misc Scala
import scala.util.Try
import scala.reflect.ClassTag
import collection.JavaConversions._
import collection.mutable._

object netCDFTest extends Logging {

  def main(args: Array[String]) = {
    val conf = new SparkConf().setAppName("testNetCDF").setMaster("local[1]")
    val sc = new SparkContext(conf)
    sys.addShutdownHook( {sc.stop() } )
    appMain(sc, args)
  }

  def appMain(sc: SparkContext, args: Array[String]) = {
    val inpath = "./src/main/resources/merg_2006091100_4km-pixel.nc"
    val outpath  = "output.nc"
    val outpath1 = "output1.nc"
    // Load using NetCDF binding
    val infileTry = Try(NetcdfFile.open(inpath))
    if (!infileTry.isSuccess) {
      logError("Couldn't open the input file " + inpath)
      sc.stop()
      System.exit(1)
    }
    val infile = infileTry.get

    // Example of loading a 2D dataset into breeze: Total Cloud Cover
    var varName = "ch4"
    var results = load2D[Float](infile, varName)
    var rows = results._1
    var cols = results._2
    // Example of loading a 3D dataset into breeze: Temperature
    varName = "ch4"
    //var results2 = loadClimate3D[Float](infile, varName)
    //var rows = results2._2
    //var cols = results2._3
    //var mat = results2._5(1,::).t.toDenseMatrix.reshape(cols, rows).t // extracts measurements at depth 1 as (lat, lon) matrix

    // Unfortunately, grib doesn't explicitly store the latitude/longitude grid, it has to be computed from the number
    // of grid points in each direction. PyGrib (in pygrib.pyx on github) has the logic to do this, so just assume that
    // the appropriate grid is known

    // Save back out using NetCDF binding

    // How to error-handle this?
    val outfile = NetcdfFileWriter.createNew(NetcdfFileWriter.Version.netcdf3, outpath, null)

    //val depthDim = outfile.addDimension(null, "time", results2._1)
    val latDim = outfile.addDimension(null, "lat", rows)
    val lonDim = outfile.addDimension(null, "lon", cols)
    val twoDims = ArrayBuffer(latDim, lonDim)
    //val threeDims = ArrayBuffer(depthDim, latDim, lonDim)

    val cloudcover = outfile.addVariable(null, "cloudcover", DataType.FLOAT, twoDims);
    //val temperature = outfile.addVariable(null, "temperature", DataType.FLOAT, threeDims);

    outfile.create
    // memory info
    val mb = 1024*1024
    val runtime = Runtime.getRuntime
    logInfo("before write first file, memory usage (MB)")
    logInfo("** Used Memory:  " + (runtime.totalMemory - runtime.freeMemory) / mb)
    logInfo("** Free Memory:  " + runtime.freeMemory / mb)
    logInfo("** Total Memory: " + runtime.totalMemory / mb)
    logInfo("** Max Memory:   " + runtime.maxMemory / mb)

    // breeze stores matrices in column-major, netcdf expects matrices in row-major format
    var dataarray = netcdfArray.factory(DataType.FLOAT, Array(rows, cols), results._3.t.data)
    //writeClimate3DToNetCDF(outfile, temperature, results2._5, threeDims)
    logInfo("results data type: "+gettype(results))
    logInfo("results._3 data type: "+gettype(results._3))
    logInfo("results._3.t data type: "+gettype(results._3.t))
    logInfo("results._3.t.data data type:"+gettype(results._3.t.data))
    outfile.write(cloudcover, dataarray)
    outfile.close
    logInfo("after write first file, before write second memory, , memory usage (MB)")
    logInfo("** Used Memory:  " + (runtime.totalMemory - runtime.freeMemory) / mb)
    logInfo("** Free Memory:  " + runtime.freeMemory / mb)
    logInfo("** Total Memory: " + runtime.totalMemory / mb)
    logInfo("** Max Memory:   " + runtime.maxMemory / mb)
    rows=1024*1400
    cols=1024
    logInfo("rows: "+rows+" cols: "+cols)
    val outlarge = NetcdfFileWriter.createNew(NetcdfFileWriter.Version.netcdf3,outpath1,null)
    //val latDimlarge= outlarge.addDimension(null,"lat",rows)
    val latDimlarge = outlarge.addUnlimitedDimension("lat")
    val lonDimlarge= outlarge.addDimension(null,"lon",cols)
    val twoDimslarge= ArrayBuffer(latDimlarge,lonDimlarge)

    val largecloud=outlarge.addVariable(null,"largecloud",DataType.INT,twoDimslarge)
    outlarge.create
    //generate large random 2d array
    val resultlarge=random2dArray(rows,cols,10000)
    logInfo("resultlarge data type: "+ gettype(resultlarge))
    val dataarraylarge=netcdfArray.factory(DataType.INT,Array(rows,cols),resultlarge)
    outlarge.write(largecloud,dataarraylarge)
    outlarge.close
    logInfo("after write second file, , memory usage (MB)")
    logInfo("** Used Memory:  " + (runtime.totalMemory - runtime.freeMemory) / mb)
    logInfo("** Free Memory:  " + runtime.freeMemory / mb)
    logInfo("** Total Memory: " + runtime.totalMemory / mb)
    logInfo("** Max Memory:   " + runtime.maxMemory / mb)
    sc.stop()
  }
  def gettype[T](v: T)(implicit ev: ClassTag[T]) = ev.toString
  // Note: NetCDF uses the row-major C convention for storing matrices: in the flattened vector, the index of the last axis changes fastest (e.g. see the help for numpy.reshape)
  // while Breeze uses the column-major Fortran convention for storing matrices
  def netCDFflatToBreeze2D[T](flatdata : Array[T], rows : Int, cols : Int ) : DenseMatrix[T] = {
    (new DenseVector(flatdata)).toDenseMatrix.copy
  }
  def random2dArray(dim1: Int, dim2: Int, maxValue: Int) = Array.fill(dim1* dim2) {Random.nextInt(maxValue)}

  def load2D[T](fin: NetcdfFile, varName: String) : Tuple3[Int, Int,DenseMatrix[T]] = {
    var variable = fin.findVariable(varName)
    logInfo("Loading " + varName + " " + variable.getFullName)
    logInfo("Loading " + varName + " " + variable.getDescription)
    logInfo(varName + " has dimensions " + variable.getDimensions)
    var rows = variable.getDimension(1).getLength
    var cols = variable.getDimension(2).getLength
    logInfo("rows:"+rows+"  "+"cols:"+cols)
    //var fillValue = variable.findAttribute("_FillValue").getNumericValue.asInstanceOf[T]
    var breezeMat = netCDFflatToBreeze2D(variable.read.copyTo1DJavaArray.asInstanceOf[Array[T]], rows, cols)
    //var maskMat = breezeMat.copy.mapValues(_ == fillValue)
    (rows, cols, breezeMat)
  }

  // returns a 3d matrix of dimensions [h, w, d] as a 2d matrix of size [d,
  // h*w] where the rows can be unfolded back to get an [h, w] matrix using the
  // row-major C convention
  def load3D[T](fin: NetcdfFile, varName: String) : Tuple6[Int, Int, Int, T, DenseMatrix[T], DenseMatrix[Boolean]] = {
    var variable = fin.findVariable(varName)
    logInfo("Loading " + varName + " " + variable.getFullName)
    logInfo("Loading " + varName + " " + variable.getDescription)
    logInfo(varName + " has dimensions " + variable.getDimensions)
    var rows = variable.getDimension(0).getLength
    var cols = variable.getDimension(1).getLength
    var depth = variable.getDimension(2).getLength
    var fillValue = variable.findAttribute("_FillValue").getNumericValue.asInstanceOf[T]
    var breezeMat = new DenseVector(variable.read.asInstanceOf[Array[T]]).toDenseMatrix
    var maskMat = breezeMat.copy.mapValues(_ == fillValue)
    (rows, cols, depth, fillValue, breezeMat, maskMat)
  }

  // the climate data is stored as (depth, lat, lon) so using load3D would return a [lon, lat*depth] matrix; it's more 
  // useful to return a [depth, lat*lon] matrix, which this routine does
  def loadClimate3D[T:ClassTag](fin: NetcdfFile, varName: String) : Tuple6[Int, Int, Int, T, DenseMatrix[T], DenseMatrix[Boolean]]= {
    var variable = fin.findVariable(varName)
    logInfo("Loading " + varName + " " + variable.getFullName)
    logInfo("Loading " + varName + " " + variable.getDescription)
    logInfo(varName + " has dimensions " + variable.getDimensions)
    var depth = variable.getDimension(0).getLength
    var rows = variable.getDimension(1).getLength
    var cols = variable.getDimension(2).getLength
    var fillValue = variable.findAttribute("_FillValue").getNumericValue.asInstanceOf[T]
    var rawdata = new Array[T](rows*cols*depth)
    for (level <- 0 until depth) {
      var origin = Array[Int](level, 0, 0)
      var shape = Array[Int](1, rows, cols)
      variable.read(origin, shape).copyTo1DJavaArray.asInstanceOf[Array[T]] copyToArray (rawdata, level*rows*cols)
    }
    var mungedMat = (new DenseVector(rawdata)).toDenseMatrix.reshape(cols*rows, depth).t.copy
    var mungedMask = mungedMat.copy.mapValues(_ == fillValue)
    (depth, rows, cols, fillValue, mungedMat, mungedMask)
  }

  // given a 3d matrix stored in breeze as a [depth, rows*cols] matrix so that each row of the matrix is unfolded into a breeze 2d matrix of
  // [rows, cols] by using row.reshape(rows, cols), stores it into a netCDF array of size [depth, rows, cols]
  // recall breeze stores data in column-major format, netcdf in row-major format
  def writeClimate3DToNetCDF[T:ClassTag](outfile: NetcdfFileWriter, variable: Variable, mat: DenseMatrix[T], dims: ArrayBuffer[Dimension]) : Unit = {
    logInfo("Writing " + variable.getFullName)
    logInfo(variable.getFullName + " has dimensions " + variable.getDimensions)
    var depth = dims(0).getLength
    var rows = dims(1).getLength
    var cols = dims(2).getLength
    for (level <- 0 until depth) {
      val origin = Array[Int](level, 0, 0)
      val scalaArray = mat(level, ::).t.toDenseMatrix.reshape(cols, rows).data.toArray
      val ncArray = netcdfArray.factory(variable.getDataType, Array(1, rows, cols), scalaArray)
      outfile.write(variable, origin, ncArray)
    }
  }

  def report(message: String, verbose: Boolean = true) {
    if (verbose) logInfo(message);
  }

}


package org.dsa.core.sparkSW

/**
  * Created by Guoguang Zhao on 2014/09/05.
  * The Smith-Waterman (SW) algorithm is universally used for a database search owing to its high sensitively. The widespread impact of the algorithm is reflected in over 8000 citations that the algorithm has received in the past decades. However, the algorithm is prohibitively high in terms of time and space complexity, and so poses significant computational challenges. Apache Spark is an increasingly popular fast big data analytics engine, which has been highly successful in implementing large-scale data-intensive applications on commodity hardware. This paper presents the first ever reported system that implements the SW algorithm on Apache Spark based computing framework, with a couple of off-the-shelf commercial clusters. The scalability and load balancing efficiency of the system were investigated by realistic ultra-large database from the state-of-the-art UniRef100. The experimental results indicated that the system shows efficient load balancing and horizontal scalability. This study revealed Spark framework provides a which is facilitate coping with ever increasing sizes of biological sequence databases.
  */

import java.io.StringReader

import au.com.bytecode.opencsv.CSVReader
import org.apache.spark._

import scala.io.Source

object SparkSW {
  def main(args: Array[String]) {
    // arguments for input files and references
    val subMatrix = args(0)
    val queryFile = args(1)
    val dbFile = args(2)
    val splitNum = args(3).toInt
    val taskNum = args(4).toInt
    val topK = args(5).toInt

    // set application name
    val appName = "SparkSW Application:" + "queryFile=" + queryFile.toString +
      ",dbFile=" + dbFile.toString + ",splitNum=" + splitNum.toString +
      ",taskNum=" + taskNum.toString + ",topK=" + topK.toString
    val conf = new SparkConf().setAppName(appName)
    //	val conf = new SparkConf().setAppName("SparkSW Application")
    if (System.getProperties.getProperty("os.name").contains("Windows")) {
      conf.setMaster("local[16]")
    }
    // initialize SparkContext
    val spark = new SparkContext(conf)

    // read substitution matrix file blosum50 from HDFS
    val inFile = spark.textFile(subMatrix)
    val blosumFile = inFile.map(line => {
      val reader = new CSVReader(new StringReader(line))
      reader.readNext()
    })
    val blosumFileArray = blosumFile.collect()
    val blosum = new Array[Array[Int]](26)
    for (i <- 0 to 25) {
      blosum(i) = new Array[Int](26)
    }
    for (i <- 0 to 25) {
      for (j <- 0 to 25) {
        blosum(i)(j) = blosumFileArray(i)(j).toInt
      }
    }
    // create broadcast variable for blosum50 matrix
    val bcBlosum = spark.broadcast(blosum)
    val bcBlosumValue = bcBlosum.value

    // read query sequence file from HDFS
    val querySource = Source.fromFile(queryFile, "UTF-8")
    val queryContent = querySource.mkString
    val queryLen = queryContent.length
    val brContent = spark.broadcast(queryContent)
    val querySeqCon = brContent.value
    var querySeqLen = querySeqCon.length + 1

    // read database sequence file from HDFS
    val proteinFile = spark.textFile(dbFile, splitNum)

    // map process of SparkSW
    val everyScore = proteinFile.map { eachLine => {
      // get name and content of each database sequences

      val eachDbSequence = eachLine.split(",")
      val eachDbSeqName = eachDbSequence(0)
      val eachDbSeqCon = eachDbSequence(1)
      var eachDbSeqLen = eachDbSeqCon.length + 1


      // new arrays and initialize them for matrices
      var F = new Array[Array[Int]](eachDbSeqLen)
      var E = new Array[Array[Int]](eachDbSeqLen)
      var H = new Array[Array[Int]](eachDbSeqLen)
      for (i <- 0 until eachDbSeqLen) {
        F(i) = new Array[Int](querySeqLen)
        F(i)(0) = 0
        E(i) = new Array[Int](querySeqLen)
        E(i)(0) = 0
        H(i) = new Array[Int](querySeqLen)
        H(i)(0) = 0
      }
      for (i <- 0 until querySeqLen) {
        F(0)(i) = 0
        E(0)(i) = 0
        H(0)(i) = 0
      }
      // initialize one maximum score to zero
      var maxScore = 0

      // core of SW algorithm
      for (i <- 1 to (eachDbSeqLen - 1)) {
        for (j <- 1 to (querySeqLen - 1)) {
          F(i)(j) = Array((F(i - 1)(j) - 2), (H(i - 1)(j) - 12)).max
          E(i)(j) = Array((E(i)(j - 1) - 2), (H(i)(j - 1) - 12)).max
          H(i)(j) = Array(0, E(i)(j), F(i)(j), (H(i - 1)(j - 1) + score(eachDbSeqCon(i - 1), querySeqCon(j - 1)))).max
          if (H(i)(j) > maxScore) {
            maxScore = H(i)(j)
          }
        }
      }

      def score(up: Char, left: Char): Int = {
        // hash for blosum50
        var upInt = up.toInt
        var leftInt = left.toInt
        var upposition = upInt - 65
        var leftposition = leftInt - 65
        var score = bcBlosumValue(leftposition)(upposition)
        return score
      }
      (maxScore, eachDbSeqName)
    } // end of eachLine
    } // end of proteinFile.map

    // filter results with query length
    val everyScoreFiltered = everyScore.filter { eachOne => {
      val eachScoreVal = eachOne._1
      if (eachScoreVal >= queryLen) {
        (true)
      }
      else {
        (false)
      }
    }
    }
    // cache everyScoreFiltered in memory
    everyScoreFiltered.persist()
    // sort everyScoreFiltered
    val FinalScore = everyScoreFiltered.sortByKey(false, taskNum)
    // take top K records from sorted everyScoreFiltered
    val TopScore = FinalScore.take(topK)
    // print the top K records
    TopScore.foreach(println)
    spark.stop()
  } // end of main
}

//end of object

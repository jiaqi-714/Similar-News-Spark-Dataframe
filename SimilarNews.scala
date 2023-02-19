package comp9313.proj3

import org.apache.spark.sql.functions.{col, udf}
import org.apache.spark.sql.{Row, SparkSession}

object SimilarNews extends Serializable {
  // ignore first word
  def checkSimilarityTwoArray( a:Array[Int], b:Array[Int] ) : Double = {
    val arrayA = a
    val arrayB = b
    val temp = arrayB.toSet
    val intersectWords = arrayA.filter(temp).length.toDouble
    val unionWords = (arrayA ++ arrayB).distinct.length.toDouble
    return intersectWords/unionWords
  }

  def main(args: Array[String]): Unit = {
    val spark: SparkSession = SparkSession.builder.master("local").getOrCreate
    val sc = spark.sparkContext// Just used to create test RDDs
    val inputFilePATH = args(0)
    val outputFolderPATH = args(1)
    val tau = args(2).toDouble

    // get tokenfreqency
    val textFile = sc.textFile(inputFilePATH).map(_.split(","))
    // filter empty title by filter(_.length == 2)
    // filter empty word "" by filter(x => x.length != 0)
    val words = textFile.filter(_.length == 2).map(x => x.tail).map(x => x(0).split(" ")).flatMap(x=>x)
    val tokensFreq = words.map(x => (x, 1)).reduceByKey(_+_).map(_.swap).sortByKey(true).map(_.swap).map(x => x._1).filter(x => x.length != 0).collect

    // (freq of token)
    var indexs = Array[Int]()
    for (i <- 0 until tokensFreq.length){
      indexs = indexs :+ i
    }
    val tokensFreqMap = (tokensFreq zip indexs).toMap
    val btokensFreqMap = sc.broadcast(tokensFreqMap)
    println("get tokensFreq====================")
    val indexTextFile = textFile.zipWithIndex().map{case(x, y) => (y.toInt, x)}

    // get array of ((index date), array(words)), remove the empty title
    // ((0,20191124),Array(woman, stabbed, adelaide, shopping, centre))
    println("get rowData====================")
    // fitler out empty title by filter(item => item._2.length != 1),
    // fitler emtpy word "" by filter(word => word.length != 0),convert string to id in tokensFreqMap,
    val wordsArray = indexTextFile.filter(item => item._2.length != 1).map(x => ((x._1,x._2(0).toInt), x._2(1))).map{case(x, y) => (x, y.split(" ").filter(word => word.length != 0).map(word => btokensFreqMap.value.getOrElse(word,0)).distinct)}

    println("get wordsArray====================")
    // get look up table for (title index: title content)
    val wordsSortArrayIndex = wordsArray.map{case(date, stringArray) => date._1}.collect
    val wordsSortArrayvalue = wordsArray.map{case(date, stringArray) => stringArray}.collect
    val wordsLookUpTable = (wordsSortArrayIndex zip wordsSortArrayvalue).toMap
    val bwordsLookUpTable = sc.broadcast(wordsLookUpTable)
    println("get sorted array====================")

    val wordsSortArray = wordsArray.map{case(date, stringArray) => (date, stringArray.sortBy(a => a))}
    // generate prefix key!!!! only include the index and year information, use tau to fitler unused key
    val wordsSortArrayKeyValuePairs = wordsSortArray.map{case((index, date), stringArray) => (stringArray.filter(s=>stringArray.indexOf(s) < (stringArray.length) - (tau*(stringArray.length)) + 1)).map(word => (word, index.toInt , date.toString.slice(0, 4).toInt))}

    println("get wordsSortArrayKeyValuePairs====================")
    //array of key value pair
    //((key ,index, date))
    val KeyValuePairs = wordsSortArrayKeyValuePairs.flatMap(x => x)

    val dfWitDefaultSchema = spark.createDataFrame(KeyValuePairs)
    val keyValuePairsDf = dfWitDefaultSchema.withColumnRenamed("_1","key").withColumnRenamed("_2","index").withColumnRenamed("_3","date")

//    create user define function in spark sql to find similarity base on col(index)
    val convertUDF = udf(( index1:Int, index2:Int) => {
      val arrayA = bwordsLookUpTable.value.get(index1).get
      val arrayB = bwordsLookUpTable.value.get(index2).get
      val temp = arrayB.toSet
      val intersectWords = arrayA.filter(temp).length.toDouble
      val unionWords = (arrayA ++ arrayB).distinct.length.toDouble
      intersectWords/unionWords
    })

    val convertUDFRegi = spark.udf.register("convertUDF", convertUDF)

    // self join the key_value pairs if key is same, fitler by date, index number. Then add one more col ("sim") base on joined table
    // filter out similar lower than tau, then order it
    val df = keyValuePairsDf.select(col("key") as "key1", col("index") as "index1" ,col("date") as "date1").join(keyValuePairsDf.select(col("key") as "key2", col("index") as "index2" ,col("date") as "date2")).where(col("key1") === col("key2") and (col("index1") < col("index2")) and (col("date1") !== col("date2"))).withColumn("sim", convertUDF(col("index1"), col("index2"))).select("index1", "index2", "sim").distinct.filter("sim >=" + tau).orderBy(col("index1").asc,col("index2").asc)


    println("get sortedOutput====================")
    val outputArray = df.rdd.map(x => (x.getAs[Int](0), x.getAs[Int](1), x.getAs[Double](2)))
    val formatOutput = outputArray.map{case(x, y, z) => s"""($x,$y)\t$z"""}
    formatOutput.saveAsTextFile(outputFolderPATH)
    sc.stop()
  }

}

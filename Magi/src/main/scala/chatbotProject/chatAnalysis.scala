package chatbotProject
// Kafka topic name - chatbotproject
import org.apache.spark.streaming._
import StreamingContext._
import org.apache.spark.sql.SparkSession
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.spark.streaming.kafka010._
import org.apache.spark.streaming.kafka010.LocationStrategies.PreferConsistent
import org.apache.spark.streaming.kafka010.ConsumerStrategies.Subscribe
import org.apache.kafka.clients.consumer.ConsumerRecords
import org.apache.spark.sql.SaveMode
import org.elasticsearch.spark.sql._



object chatAnalysis {
  def main(args:Array[String])
  {
    // creating a sparksession object
    val sparksession = SparkSession.builder().appName("Chat Analysis").master("local[*]").
                       config("spark.history.fs.logDirectory","file:///tmp/spark-events").
                       config("spark.eventlog.dir","file:///tmp/spark-events").
                       config("spark.events.enabled","true").
                       config("hive.metastore.uris","thrift://127.0.0.1:9083").
                       config("spark.sql.warehouse.dir","hdfs://127.0.0.1:50070/user/hive/warehouse"). //this is namenode URL. we can use the particular HIVE datanode/HIVE installed also
                       config("spark.sql.shuffle.partition",10).
                       config("spark.es.nodes","127.0.0.1").
                       config("spark.es.port",9200).
                       config("es.nodes.wan.only","true").
                       enableHiveSupport().getOrCreate();
    sparksession.sparkContext.setLogLevel("ERROR");
    //print("sparksession: " + sparksession);
    // creating a streaming object
    val streaming = new StreamingContext(sparksession.sparkContext, Seconds(5)); // 5 second interval the application will run
    // list of topics to be read from Kafka
    val topics = Array("chatbotproject");
    //creating kafka params to be used in create direct stream
    val kafkaparams = Map[String, Object](
                        "bootstrap.servers"   ->   "127.0.0.1:9092",
                        "key.deserializer"    ->   classOf[StringDeserializer],
                        "value.deserializer"  ->   classOf[StringDeserializer],
                        "group.id"            ->   "Muniappan",
                        "auto.offset.reset"   ->   "earliest", // used earliest for testing
                        "enable.auto.commit"  ->   (false:java.lang.Boolean)
                      );
    //create a Dstream with kafka params, location stratgey and topics
    val dis_stream = KafkaUtils.createDirectStream [String, String](streaming, PreferConsistent, Subscribe[String, String] (topics, kafkaparams));
    
    try
    {
      dis_stream.foreachRDD{
            iterateRDD => 
              val kafkaOffsets = iterateRDD.asInstanceOf[HasOffsetRanges].offsetRanges
              println("Printing offset ranges: ")
              kafkaOffsets.foreach(println)
              if (!iterateRDD.isEmpty())
              {
                import sparksession.sqlContext.implicits._
                import org.apache.spark.sql.Column
                val jsonRDD = iterateRDD.map(x=>x.value())
                val jsonDF = sparksession.read.option("multiline", "true").option("mode","DROPMALFORMED").json(jsonRDD)
                //jsonDF.printSchema()
                """
                val drop_col_list = Seq("agentcustind, updts")
                val cust_jsonDF = jsonDF.where("agentcustind = 'c'")
                println("cust_jsonDF.drop(drop_col_list:_*)")
                cust_jsonDF.drop(drop_col_list:_*).printSchema()
                val cust_jsonDF_trim = cust_jsonDF.drop(drop_col_list:_*)
                cust_jsonDF_trim.printSchema()
                cust_jsonDF_trim.createOrReplaceTempView("cust_chats")
                cust_jsonDF_trim.show()
                //val cust_jsonDF_split = cust_jsonDF.select(split("chattext"))
                """
                jsonDF.createOrReplaceTempView("jsonDF_view")
                val jsonDF_2_cols = sparksession.sql("""select temp_table.custid custid, explode(temp_table.split_chat_text) split_chat_text from 
                                                      (select id as custid, split(chattext,' ') as split_chat_text from jsonDF_view where agentcustind = 'c') temp_table""")
                jsonDF_2_cols.createOrReplaceTempView("chat_temp_view")                                      
                //jsonDF_2_cols.show(200,false)
                // Adding hi also in the stop words
                val stop_words_DF = sparksession.read.text("file:///home/hduser/projects/Nifi_use_case/stopwords").toDF("stopwords")
                //stop_words_DF.printSchema()
                stop_words_DF.createOrReplaceTempView("stop_words")
                //Putting distinct as some words are repeated in the stopwords file
                val final_result_DF = sparksession.sql("""select a.custid,a.split_chat_text from chat_temp_view a where a.split_chat_text not in 
                                (select distinct stopwords from stop_words)""")
                final_result_DF.show(200, false)
                println("Hive table name - nifi_use_case.final_result_DF")
                """
                sparksession.sql("drop table if exists nifi_use_case.final_result_DF")
                final_result_DF.write.mode(SaveMode.Overwrite).saveAsTable("nifi_use_case.final_result_DF")
                //sparksession.sql("select * from nifi_use_case.final_result_DF").show(false)
                """
                final_result_DF.write.mode(SaveMode.Append).saveAsTable("nifi_use_case.final_result_DF")
                println("Most words used by customers")
                val final_result_DF_ES = sparksession.sql("select split_chat_text as words_typed, cast(count(1) as integer) as occurrence from nifi_use_case.final_result_DF group by split_chat_text")
                final_result_DF_ES.show(false)
                """
                curl -X DELETE 'http://localhost:9200/chatindex?pretty'
                curl -XPUT 'http://localhost:9200/chatindex' -d '
                {
                "mappings": {
                "words": {
                "properties": {
                "words_typed": {
                "type": "keyword"
                },
                "occurrence": {
                "type": "integer"
                }
                }
                }
                }
                }'  
                curl -XPUT 'http://localhost:9200/chatindex/words/test' -d '{"occurrence":"1"}'
                """
                final_result_DF_ES.saveToEs("chatindex/words",Map("es.mapping.id"->"words_typed"))
                
                
                //Committing the offset values to Kafka
                dis_stream.asInstanceOf[CanCommitOffsets].commitAsync(kafkaOffsets)
                
                println("completed after committing the offsets to Kafka")
              }
              else
              {
                println(java.time.LocalTime.now())
                println("No data in the Dstream RDD")
              }
      }     
    }
    catch // catch block is added here for a temporary purpose
     {
      case exception1: java.lang.NullPointerException => {
        println("Null pointer exception")
      }
     }
    streaming.start()
    streaming.awaitTermination()
  }
}
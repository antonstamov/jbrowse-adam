package md.fusionworks.adam.jbrowse.models

import com.typesafe.config.ConfigFactory
import htsjdk.samtools.SAMFileHeader
import md.fusionworks.adam.jbrowse.spark.SparkContextFactory
import org.apache.spark.sql.functions._
import org.bdgenomics.adam.converters.AlignmentRecordConverter
import org.bdgenomics.adam.models.SAMFileHeaderWritable
import org.bdgenomics.adam.rdd.ADAMContext._
import org.bdgenomics.formats.avro.AlignmentRecord
import parquet.org.codehaus.jackson.map.ObjectMapper
import spray.json.DefaultJsonProtocol


object JsonProtocol extends DefaultJsonProtocol {
  implicit val trackFormat = jsonFormat5(Track)
  implicit val trakListFormat = jsonFormat1(TrackList)
  implicit val refSeqsFormat = jsonFormat3(RefSeqs)
  implicit val globalFormat = jsonFormat6(Global)
  implicit val featureFormat = jsonFormat17(Feature)
  implicit val featuresFormat = jsonFormat1(Features)
}

object JbrowseUtil {

  private var headerMap = Map[String, SAMFileHeader]()
  val sc = SparkContextFactory.getSparkContext
  val sqlContext = SparkContextFactory.getSparkSqlContext

  val adamPath = ConfigFactory.load().getString("adam.path")

  val dataFrame = sqlContext.read.parquet(adamPath)
  val mapper = new ObjectMapper()

  def getTrackList: TrackList = {
    TrackList(tracks = List(
      Track(
        "mygene_track",
        "My ADAM Genes",
        "JBrowse/View/Track/Alignments2",
        "JBrowse/Store/SeqFeature/REST",
        "http://localhost:8080/data"
      ),
      Track(
        "my_sequence_track",
        "DNA",
        "JBrowse/View/Track/Sequence",
        "JBrowse/Store/SeqFeature/REST",
        "http://localhost:8080/data"
      )))
  }

  def getRefSeqs: List[RefSeqs] = {
    val filteredDataFrame = dataFrame.filter(dataFrame("start") >= 0 && dataFrame("start") != null)
    val colectDataFrame = filteredDataFrame.select("contig", "start", "end")
      .groupBy("contig.contigName").agg(min("start"), max("end"))
      .orderBy("contigName").collect().toList
    colectDataFrame.map(x =>
      RefSeqs(
        name = x.getString(0),
        start = x.getLong(1),
        end = x.getLong(2)
      ))
  }

  def getGlobal: Global = Global(0.02, 234235, 87, 87, 42, 2.1)

  def getFeatures(start: Long, end: Long, contigName: String): Features = {

    val filteredDataFrame = dataFrame.filter(dataFrame("contig.contigName") === contigName)
      .filter(
        dataFrame("start") < end && dataFrame("start") != null &&
          dataFrame("end") > start && dataFrame("end") != null
      )

    val alignmentRecordsRDD = filteredDataFrame.toJSON.map(str => mapper.readValue(str, classOf[AlignmentRecord]))
    val converter = new AlignmentRecordConverter


    val header =  headerMap.get(contigName) match {
      case Some(h) =>
        h

      case None =>
        val sd = alignmentRecordsRDD.adamGetSequenceDictionary()
        val rgd = alignmentRecordsRDD.adamGetReadGroupDictionary()

       val h =  converter.createSAMHeader(sd, rgd)
        headerMap += (contigName -> h)
        headerMap(contigName)
    }

    val hdrBcast = alignmentRecordsRDD.context.broadcast(SAMFileHeaderWritable(header))

/*    val featureList = alignmentRecordsRDD.map(x => {
      val samRecord = converter.convert(x, hdrBcast.value)
      Feature(
        name = x.getReadName,
        seq = x.getSequence,
        start = x.getStart,
        end = x.getEnd,
        cigar = x.getCigar,
        map_qual = x.getMapq,
        mate_start = x.getMateAlignmentStart,
        uniqueID = x.getReadName + "_" + x.getStart,
        qual = x.getQual.map(_ - 33).mkString(" "),
        flag = samRecord.getFlags,
        insert_size = samRecord.getInferredInsertSize,
        ref_index = samRecord.getReferenceIndex,
        mate_ref = samRecord.getMateReferenceName,
        mate_ref_index = samRecord.getMateReferenceIndex,
        as = samRecord.getAttributesBinarySize,
        strand = if (samRecord.getReadNegativeStrandFlag) 1 else -1,
        rg = x.getRecordGroupName
      )
    }).collect().sortBy(_.start).toList
    */

    val featureList = alignmentRecordsRDD.map(x => {
      val samRecord = converter.convert(x, hdrBcast.value)
      var maps = Map(
      "name" -> x.getReadName,
      "seq" -> x.getSequence,
      "start" ->x.getStart.toString,
      "end" -> x.getEnd.toString,
      "cigar" -> x.getCigar,
      "map_qual" -> x.getMapq.toString,
      "mate_start" -> x.getMateAlignmentStart.toString,
      "uniqueID" -> (x.getReadName + "_" + x.getStart),
      "qual" -> (x.getQual.map(_ - 33).mkString(" ")).toString,
      "flag" -> samRecord.getFlags.toString,
      "insert_size" -> samRecord.getInferredInsertSize.toString,
      "ref_index" -> samRecord.getReferenceIndex.toString,
      "mate_ref_index" -> samRecord.getMateReferenceIndex.toString,
      "as" -> samRecord.getAttributesBinarySize.toString,
      "strand" -> (if (samRecord.getReadNegativeStrandFlag) 1 else -1).toString
      )
      if (!x.getRecordGroupName.isEmpty)
        maps+=("rg" ->x.getRecordGroupName)
      maps
    }).collect().toList.sortBy(x => x("start"))


    Features(features = featureList)
  }

}

case class TrackList(tracks: List[Track])

case class Track(
                  label: String,
                  key: String,
                  `type`: String,
                  storeClass: String,
                  baseUrl: String
                  )

case class RefSeqs(
                    name: String,
                    start: Long,
                    end: Long
                    )

case class Global(
                   featureDensity: Double,
                   featureCount: Int,
                   scoreMin: Int,
                   scoreMax: Int,
                   scoreMean: Int,
                   scoreStdDev: Double
                   )

case class Features(features: List[Map[String,String]])

case class Feature(
                    name: String,
                    seq: String,
                    start: Long,
                    end: Long,
                    cigar: String,
                    map_qual: Int,
                    mate_start: Long,
                    uniqueID: String,
                    qual: String,
                    flag: Int,
                    insert_size: Long,
                    ref_index: Int,
                    mate_ref: String,
                    mate_ref_index: Int,
                    as: Int,
                    strand: Int,
                    rg: String
                    )

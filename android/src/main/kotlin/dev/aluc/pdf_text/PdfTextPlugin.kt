package dev.aluc.pdf_text

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.FlutterPlugin.FlutterPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result

import java.io.File


import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlin.concurrent.thread

/** PdfTextPlugin */
class PdfTextPlugin: MethodCallHandler, FlutterPlugin {

  /**
   * PDF document cached from the previous use.
   */
  private var cachedDoc: PDDocument? = null
  private var cachedDocPath: String? = null
  private var applicationContext: Context? = null
  private var methodChannel: MethodChannel? = null

  /**
   * PDF text stripper.
   */
  private var pdfTextStripper: PDFTextStripper? = null

  companion object {
    private const val CHANNEL_NAME = "pdf_text"
  }

  override fun onAttachedToEngine(binding: FlutterPluginBinding) {
    applicationContext = binding.applicationContext
    PDFBoxResourceLoader.init(applicationContext)
    pdfTextStripper = PDFTextStripper()
    methodChannel = MethodChannel(binding.binaryMessenger, CHANNEL_NAME)
    methodChannel!!.setMethodCallHandler(this)
  }

  override fun onMethodCall(call: MethodCall, result: Result) {
    thread (start = true) {
      when (call.method) {
          "initDoc" -> {
            val args = call.arguments as Map<String, Any>
            val path = args["path"] as String
            val password = args["password"] as String
            val fastInit = args["fastInit"] as Boolean
            initDoc(result, path, password, fastInit)
          }
          "getDocPageText" -> {
            val args = call.arguments as Map<String, Any>
            val path = args["path"] as String
            val pageNumber = args["number"] as Int
            getDocPageText(result, path, pageNumber)
          }
          "getDocText" -> {
            val args = call.arguments as Map<String, Any>
            val path = args["path"] as String
            val missingPagesNumbers = args["missingPagesNumbers"] as List<Int>
            getDocText(result, path, missingPagesNumbers)
          }
          else -> {
            Handler(Looper.getMainLooper()).post {
              result.notImplemented()
            }

          }
      }
    }
  }

  override fun onDetachedFromEngine(binding: FlutterPluginBinding) {
    applicationContext = null
    cachedDoc?.close()
    methodChannel!!.setMethodCallHandler(null)
    methodChannel = null
  }

  /**
    Initializes the PDF document and returns some information into the channel.
   */
  private fun initDoc(result: Result, path: String, password: String, fastInit: Boolean) {
    val doc = getDoc(result, path, password, !fastInit) ?: return
    // Getting the length of the PDF document in pages.
    val length = doc.numberOfPages

    val data = hashMapOf<String, Any>(
            "length" to length,
            "info" to hashMapOf("author" to "",
                    "creationDate" to null,
                    "modificationDate" to null,
                    "creator" to "",
                    "producer" to "",
                    "keywords" to null,
                    "title" to "",
                    "subject" to ""
            )
    )

    Handler(Looper.getMainLooper()).post {
      result.success(data)
    }
  }

  /**
   * Splits a string of keywords into a list of strings.
   */
  private fun splitKeywords(keywordsString: String?): List<String>? {
    if (keywordsString == null) {
      return null
    }
    val keywords = keywordsString.split(",").toMutableList()
    for (i in keywords.indices) {
      var keyword = keywords[i]
      keyword = keyword.dropWhile { it == ' ' }
      keyword = keyword.dropLastWhile { it == ' ' }
      keywords[i] = keyword
    }
    return keywords
  }

  /**
    Gets the text  of a document page, given its number.
   */
  private fun getDocPageText(result: Result, path: String, pageNumber: Int) {
    val doc = getDoc(result, path) ?: return
    pdfTextStripper?.startPage = pageNumber
    pdfTextStripper?.endPage = pageNumber
    val text = pdfTextStripper?.getText(doc)
    Handler(Looper.getMainLooper()).post {
      result.success(text)
    }
  }

  /**
  Gets the text of the entire document.
  In order to improve the performance, it only retrieves the pages that are currently missing.
   */
  private fun getDocText(result: Result, path: String, missingPagesNumbers: List<Int>) {
    val doc = getDoc(result, path) ?: return
    val missingPagesTexts = arrayListOf<String>()
    missingPagesNumbers.forEach {
      pdfTextStripper?.startPage = it
      pdfTextStripper?.endPage = it
      try {
        missingPagesTexts.add(pdfTextStripper?.getText(doc) ?: "")
      } catch (e: Exception) {
        missingPagesTexts.add("")
      }
    }
    Handler(Looper.getMainLooper()).post {
      result.success(missingPagesTexts)
    }
  }

  /**
  Gets a PDF document, given its path.
  Initializes the text stripper engine if initTextStripper is true.
   */
  private fun getDoc(result: Result, path: String, password: String = "",
    initTextStripper: Boolean = true): PDDocument? {
    // Checking for cached document
    if (cachedDoc != null && cachedDocPath == path) {
      return cachedDoc
    }
    return try {
      val doc = PDDocument.load(File(path), password, MemoryUsageSetting.setupTempFileOnly())
      cachedDoc = doc
      cachedDocPath = path
      if (initTextStripper) {
        initTextStripperEngine(doc)
      }
      doc
    } catch (e: Exception) {
      Handler(Looper.getMainLooper()).post {
        result.error("INVALID_PATH",
                "File path or password (in case of encrypted document) is invalid",
                null)
      }
      null
    }
  }

  /**
   * Initializes the text stripper engine. This can take some time.
   */
  private fun initTextStripperEngine(doc: PDDocument) {
    pdfTextStripper?.startPage = 1
    pdfTextStripper?.endPage = 1
    pdfTextStripper?.getText(doc)
  }
}

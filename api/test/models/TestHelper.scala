package models

import java.nio.file.{Paths, Files}
import java.nio.charset.StandardCharsets

import play.api.libs.json._
import com.gilt.apidocgenerator.models.json._
import com.gilt.apidocgenerator.models.Service
import generator.ScalaService
import lib.Text

object TestHelper {

  def writeToFile(path: String, contents: String) {
    val outputPath = Paths.get(path)
    val bytes = contents.getBytes(StandardCharsets.UTF_8)
    Files.write(outputPath, bytes)
  }

  def readFile(path: String): String = {
    scala.io.Source.fromFile(path).getLines.mkString("\n")
  }

  def parseFile(filename: String): Service = {
    service(readFile(filename))
  }

  def assertEqualsFile(filename: String, contents: String) {
    if (contents.trim != readFile(filename).trim) {
      val tmpPath = "/tmp/apidoc.tmp." + Text.safeName(filename)
      TestHelper.writeToFile(tmpPath, contents.trim)
      sys.error(s"Test output did not match. diff $tmpPath $filename")
    }
  }

  def service(json: String): Service = {
    Json.parse(contents).asOpt[Service] match {
      case None => sys.error(s"Invalid api.json file[${filename}]: " + validator.errors.mkString("\n"))
      case Some(s) => s
    }
  }

  def scalaService(json: String): ScalaService = {
    ScalaService(service(json))
  }

}

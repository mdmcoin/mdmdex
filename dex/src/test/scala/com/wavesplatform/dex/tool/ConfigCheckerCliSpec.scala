package com.wavesplatform.dex.tool

import com.typesafe.config.ConfigValueFactory
import com.wavesplatform.dex.settings.BaseSettingsSpecification
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers

import scala.jdk.CollectionConverters._

class ConfigCheckerCliSpec extends BaseSettingsSpecification with Matchers with EitherValues {

  "ConfigChecker" should "match fully config sample" in {
    val cfg = loadCleanConfigSample()
    val result = ConfigChecker.checkConfig(cfg)
    result.value shouldBe Seq.empty[String]
  }

  it should "find unexpected value in path TN.dex.bla-bla-value" in {
    val blablaValue = "TN.dex.bla-bla-value"
    val cfg = loadCleanConfigSample().withValue(
      blablaValue,
      ConfigValueFactory.fromAnyRef("some-simple-value")
    )

    val result = ConfigChecker.checkConfig(cfg)
    result.value shouldBe Seq(cutWavesDexSection(blablaValue))
  }

  it should "find unexpected value in path TN.dex.order-fee.-1.dynamic.bla-bla-value" in {
    val blablaValuePath = "TN.dex.order-fee.-1.dynamic.bla-bla-value"
    val cfg = loadCleanConfigSample().withValue(
      blablaValuePath,
      ConfigValueFactory.fromAnyRef("some-simple-value")
    )

    val result = ConfigChecker.checkConfig(cfg)
    result.value shouldBe Seq(cutWavesDexSection(blablaValuePath))
  }

  it should "find more than one unexpected values" in {
    val blablaValuePathSeq = Seq(
      "TN.dex.order-fee.-1.dynamic.bla-bla-value",
      "TN.dex.bla-value",
      "TN.dex.order-db.some-unexpected-path"
    )
    val cfg = blablaValuePathSeq.foldLeft(loadCleanConfigSample()) {
      (cfg, path) =>
        cfg.withValue(path, ConfigValueFactory.fromAnyRef("some-simple-value"))
    }
    val result = ConfigChecker.checkConfig(cfg)
    result.value should contain theSameElementsAs blablaValuePathSeq.map(cutWavesDexSection)
  }

  it should "ignore unknown values from skipped paths" in {
    val skippedProperties = Seq("events-queue.kafka.consumer.client", "events-queue.kafka.producer.client", "events-queue.kafka.servers")
    val blablaValuePathSeq = Seq(
      "TN.dex.order-fee.-1.dynamic.bla-bla-value",
      "TN.dex.bla-value",
      "TN.dex.order-db.some-unexpected-path"
    )
    val cfg = (blablaValuePathSeq ++ skippedProperties).foldLeft(
      loadCleanConfigSample()
    ) { (cfg, path) =>
      cfg.withValue(path, ConfigValueFactory.fromAnyRef("some-simple-value"))
    }.withValue("TN.dex.cli.ignore-unused-properties", ConfigValueFactory.fromIterable(skippedProperties.asJava))
    val result = ConfigChecker.checkConfig(cfg)
    result.value should contain theSameElementsAs blablaValuePathSeq.map(cutWavesDexSection)
  }

  private def cutWavesDexSection(str: String): String = str.drop("TN.dex.".length)

}

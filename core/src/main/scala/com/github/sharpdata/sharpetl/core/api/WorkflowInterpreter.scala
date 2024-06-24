package com.github.sharpdata.sharpetl.core.api

import com.github.sharpdata.sharpetl.core.repository.model.JobLog
import com.github.sharpdata.sharpetl.core.syntax.WorkflowStep
import com.github.sharpdata.sharpetl.core.util.Constants.{BooleanString, DataSourceType, IncrementalType}
import com.github.sharpdata.sharpetl.core.annotation.Annotations.{Private, Stable}
import com.github.sharpdata.sharpetl.core.api.WorkflowInterpreter._
import com.github.sharpdata.sharpetl.core.datasource.config.{DataSourceConfig, FileDataSourceConfig, TransformationDataSourceConfig}
import com.github.sharpdata.sharpetl.core.exception.Exception._
import com.github.sharpdata.sharpetl.core.quality.QualityCheck
import com.github.sharpdata.sharpetl.core.repository.StepLogAccessor.stepLogAccessor
import com.github.sharpdata.sharpetl.core.util.HDFSUtil.downloadFileToHDFS
import com.github.sharpdata.sharpetl.core.util.IncIdUtil.NumberStringPadding
import com.github.sharpdata.sharpetl.core.util.{ETLLogger, StringUtil}
import com.google.common.base.Strings.isNullOrEmpty
import org.apache.commons.lang3.SerializationUtils
import org.apache.commons.lang3.reflect.FieldUtils

import java.lang.reflect.Field

@Stable(since = "1.0.0")
trait WorkflowInterpreter[DataFrame] extends Serializable with QualityCheck[DataFrame] with AutoCloseable { // scalastyle:ignore

  def evalSteps(steps: List[WorkflowStep],
                jobLog: JobLog,
                variables: Variables,
                start: String,
                end: String): Unit = {
    try {
      steps
        .foreach(step => evalStep(step, jobLog, variables, start, end))
    } catch {
      case _: EmptyDataException =>
        if (steps.exists(step => step.skipFollowStepWhenEmpty == true.toString)) { // scalastyle:ignore
          ETLLogger.info("Data empty! and skip follow steps!")
        }
      case ex: Exception => throw ex
    }
  }

  // scalastyle:off
  def evalStep(step: WorkflowStep, jobLog: JobLog, variables: Variables, start: String, end: String): Unit = {

    val stepLog = jobLog.createStepLog(step.step)
    stepLog.setSourceType(step.source.dataSourceType)
    stepLog.setTargetType(step.target.dataSourceType)
    stepLogAccessor.create(stepLog)

    def doRead(step: WorkflowStep, variables: Variables): DataFrame = {
      applyConf(step.conf)
      updateVariablesForRefreshAutoIncMode(jobLog, variables)
      prepareSql(jobLog, variables, start, end, step)
      stepLog.info(s"[Step]: \n$step")
      stepLogAccessor.update(stepLog)
      read(jobLog, variables, step)
    }

    try {
      val df =
        if (!isNullOrEmpty(step.loopOver)) {
          executeSqlToVariables(s"SELECT * FROM `${step.loopOver}`")
            .map { newVariables =>
              val newStep = SerializationUtils.clone(step)
              doRead(newStep, variables.++(newVariables))
            }
            .reduce((left, right) => union(left, right))
        } else {
          doRead(step, variables)
        }

      if (df != null) {
        /**
         * for [[IncrementalType.AUTO_INC_ID]] job to store max id, if is refresh job, end should not be "0".padding()
         */
        if (jobLog.logDrivenType == IncrementalType.AUTO_INC_ID && variables.contains("${upperBound}")) {
          if (jobLog.dataRangeEnd == "0".padding()) {
            jobLog.dataRangeEnd = variables("${upperBound}").padding()
            ETLLogger.info(s"Setting dataRangeEnd to ${jobLog.dataRangeEnd}")
          }
        }
        val passed = {
          if (step.source.options.keys.toSeq.exists(_.contains("qualityCheckRules"))) {
            qualityCheck(
              step,
              jobLog.jobId,
              jobLog.jobName,
              df
            ).passed
          } else {
            df
          }
        }
        executeWrite(jobLog, passed, step, variables)
      }
      stepLog.success()
    } catch {
      case e: NoFileFoundException =>
        stepLog.failed(e)
        if (step.throwExceptionIfEmpty == BooleanString.TRUE) {
          throw NoFileToContinueException(step.step)
        } else {
          throw NoFileSkipException(step.step)
        }
      case e: EmptyDataException =>
        if (step.skipFollowStepWhenEmpty == true.toString) {
          stepLog.failed(e)
          throw e
        }
        stepLog.success()
      case t: Throwable =>
        stepLog.failed(t)
        throw StepFailedException(t, step.step)
    } finally {
      stepLogAccessor.update(stepLog)
    }
  }

  // scalastyle:on

  def read(jobLog: JobLog,
           variables: Variables,
           step: WorkflowStep): DataFrame = {
    step.getSourceConfig.getDataSourceType match {
      case DataSourceType.HDFS |
           DataSourceType.JSON |
           DataSourceType.EXCEL |
           DataSourceType.CSV |
           DataSourceType.FTP |
           DataSourceType.SCP =>
        val files = listFiles(step)
        jobLog.file = files.map(StringUtil.getFileNameFromPath).mkString(",")
        val df = readFile(step, jobLog, variables, files)
        cleanUpTempFiles(step, files)
        df
      case DataSourceType.SFTP =>
        ETLLogger.info("Be run file list: \n%s".format(jobLog.file))
        downloadFileToHDFS(step, jobLog, variables)
        null.asInstanceOf[DataFrame] // scalastyle:ignore
      case DataSourceType.MOUNT =>
        ETLLogger.info("Be run file list: \n%s".format(jobLog.file))
        val hdfsPaths = downloadFileToHDFS(step, jobLog, variables)
        variables.put("FILE_PATHS", hdfsPaths.mkString(","))
        null.asInstanceOf[DataFrame] // scalastyle:ignore
      case _ =>
        executeRead(step, jobLog, variables)
    }
  }

  @deprecated def listFiles(step: WorkflowStep): List[String]

  @deprecated def cleanUpTempFiles(step: WorkflowStep,
                       files: List[String]): Unit = {
    val sourceConfig = step.getSourceConfig[FileDataSourceConfig]
    if (sourceConfig.deleteSource.toBoolean) {
      files.foreach { filePath =>
        sourceConfig.setFilePath(filePath)
        deleteSource(step)
      }
    }
  }

  @deprecated def deleteSource(step: WorkflowStep): Unit

  @deprecated def readFile(step: WorkflowStep,
               jobLog: JobLog,
               variables: Variables,
               files: List[String]): DataFrame


  def executeWrite(jobLog: JobLog,
                   df: DataFrame,
                   step: WorkflowStep,
                   variables: Variables): Unit

  def executeRead(step: WorkflowStep,
                  jobLog: JobLog,
                  variables: Variables): DataFrame

  override def close(): Unit = ()

  def applyConf(conf: Map[String, String]): Unit = ()

  def applicationId(): String

  def executeSqlToVariables(sql: String): List[Map[String, String]] = List()

  def union(left: DataFrame, right: DataFrame): DataFrame
}

@Private
object WorkflowInterpreter {
  def updateVariablesForRefreshAutoIncMode(jobLog: JobLog, variables: Variables): Unit = {
    if (jobLog.logDrivenType == IncrementalType.AUTO_INC_ID
      && variables.contains("${upperBound}")
      && jobLog.dataRangeEnd != "0".padding()
    ) {
      variables.put("${upperBound}", jobLog.dataRangeEnd.trimPadding())
      ETLLogger.info(s"Setting variables $${upperBound} to ${variables("${upperBound}")}")
    }
  }

  def replaceVariablesInSql(step: WorkflowStep,
                            variables: Variables): Unit = {
    var selectSql = step.getSql
    if (selectSql != null) {
      variables.foreach {
        case (varName, varValue) =>
          selectSql = selectSql.replace(varName, varValue)
      }
      step.setSql(selectSql)
    }
  }

  def replaceVariablesInOptionsAndArgs(step: WorkflowStep,
                                       variables: Variables): Unit = {
    val newSourceOpts = replaceValues(step.getSourceConfig.getOptions.toMap, variables)
    step.getSourceConfig.setOptions(newSourceOpts)

    val newTargetOpts = replaceValues(step.getTargetConfig.getOptions.toMap, variables)
    step.getTargetConfig.setOptions(newTargetOpts)

    step.source match {
      case conf: TransformationDataSourceConfig =>
        val newSourceArgs = replaceValues(conf.getArgs.toMap, variables)
        conf.setArgs(Map(newSourceArgs.toSeq: _*))
      case _ => ()
    }

    step.target match {
      case conf: TransformationDataSourceConfig =>
        val newTargetArgs = replaceValues(conf.getArgs.toMap, variables)
        conf.setArgs(Map(newTargetArgs.toSeq: _*))
      case _ => ()
    }
  }

  def replaceValues(options: Map[String, String],
                    variables: Variables): Map[String, String] = {
    options.map {
      case (key, value) =>
        var newValue = value
        variables.foreach {
          case (varName, varValue) =>
            newValue = newValue.replace(varName, varValue)
        }
        key -> newValue
    }
  }

  def replaceTemplateVariables(conf: DataSourceConfig, variables: Variables): Unit = {
    val clazz = conf.getClass
    val fields = FieldUtils.getAllFields(clazz).filter(
      it => !it.getName.contains("$init$") && it.getType.getName == "java.lang.String"
    )
    fields.foreach(replaceValue(_, variables, conf))
  }

  def replaceValue(field: Field, variables: Variables, conf: DataSourceConfig): Unit = {
    field.setAccessible(true)
    var value = field.get(conf).asInstanceOf[String]
    if (value != null) {
      variables.foreach { case (k, v) => value = value.replace(k, v) }
      field.set(conf, value)
    }
  }

  /**
   * 对sql template进行参数替换
   */
  def prepareSql(jobLog: JobLog, variables: Variables, start: String, end: String, step: WorkflowStep): Unit = {
    if (step.getSqlTemplate != null) {
      step.setSql(
        step
          .getSqlTemplate
          .replace("${JOB_ID}", jobLog.jobId.toString)
          .replace("${DATA_RANGE_START}", start)
          .replace("${DATA_RANGE_END}", end)
      )
      replaceVariablesInSql(step, variables.filter(it => it._1.startsWith("$")))
    }
    replaceVariablesInOptionsAndArgs(step, variables)
    replaceTemplateVariables(step.source, variables)
    replaceTemplateVariables(step.target, variables)
  }
}
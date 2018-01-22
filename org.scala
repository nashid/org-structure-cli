#!/bin/sh
exec scala -savecompiled "$0" "$@"
!#

import java.io.{BufferedWriter, FileWriter}

import scala.util.Try

Try {
  execute()
}.recover {
  case ex: Exception =>
    Console.err.println(ex.getMessage)
    System.exit(1)
}

def execute(): Unit = {
  args.toList match {
    case "add" :: "member" :: id :: Nil =>
      Members.add(id)
    case "update" :: "member" :: id :: field :: value :: Nil =>
      Members.update(id, Overwrite, field, value)
    case "update" :: "member" :: id :: action :: field :: value :: Nil =>
      Members.update(id, MultiValueFieldAction(action), field, value)
    case "remove" :: "member" :: id :: Nil =>
      Members.remove(id)
    case "validate" :: Nil =>
      Members.data.validate()
      Teams.data.validate()
      Titles.data.validate()
    case _ =>
      sys.error(CmdLineUtils.Usage)
  }
}

object CmdLineUtils {
  import io.StdIn._

  val Usage =
    """
      |Usage:
      |  org add member [id]
      |  org update member [id] [field] [value]
      |  org update member [id] add|remove [field] [value] #For multi-value fields
      |  org remove member [id]
      |  org get member [id]
      |  org find member [field] [value]
    """.stripMargin

  private case class IndexedValue(index: Int, value: String)

  def promptForValue(field: Field): String = {
    val prompt =
      if (field.multiValue)
        s"Please enter '|' separated values for ${field.name}: "
      else
        s"Please enter a value for ${field.name}: "

    val validValues =
      if (field.teamRef)
        Teams.data.ids
      else if (field.titleRef)
        Titles.data.ids
      else
        Nil

    val value = readLine(prompt)
    if (validValues.nonEmpty && !validValues.contains(value)) {
      val validValueLookup = validValues.zipWithIndex.map { vv => IndexedValue(vv._2+1, vv._1) }

      val indexPrompt =
        s"""
           |Invalid value. Choose from:
           |  ${validValueLookup.map { v => s"${v.index}: ${v.value}" }.mkString("\n")}
        """.stripMargin

      promptForIndexedValue(indexPrompt, validValueLookup)
    } else {
      value
    }
  }

  private def promptForIndexedValue(indexPrompt: String, validValueLookup: Seq[IndexedValue]): String = {
    val index = readLine(indexPrompt).toInt

    validValueLookup.find { _.index == index }.map(_.value).getOrElse {
      promptForIndexedValue(indexPrompt, validValueLookup)
    }
  }
}

sealed trait FieldAction {
  def update(currentValue: String, change: String): String
}

case object Overwrite extends FieldAction {
  override def update(currentValue: String, change: String) = change
}

sealed trait MultiValueFieldAction extends FieldAction {
  val Delimiter = "|"
}

object MultiValueFieldAction {
  def apply(value: String): MultiValueFieldAction = {
    value match {
      case "add" => MultiValueAdd
      case "remove" => MultiValueRemove
      case _ => sys.error(s"Unknown action [$value]")
    }
  }
}

case object MultiValueAdd extends MultiValueFieldAction {
  override def update(currentValue: String, change: String) = {
    (currentValue.split(Delimiter) :+ change).mkString(Delimiter)
  }
}

case object MultiValueRemove extends MultiValueFieldAction {
  override def update(currentValue: String, change: String) = {
    currentValue.split(Delimiter).filterNot(_ == change).mkString(Delimiter)
  }
}

object Members {
  private val Filename = "members.csv"

  def data: Csv = Csv(Filename)

  def add(id: String): Unit = {
    val newRow = data.header.fields.map { field =>
      if (field.id)
        id
      else
        CmdLineUtils.promptForValue(field)
    }

    val newData = data.addRow(newRow)
    Csv.write(Filename, newData)
  }

  def update(id: String, action: FieldAction, field: String, value: String): Unit = {
    val newData = data.updateFieldValue(id, action, field, value)
    Csv.write(Filename, newData)
  }

  def remove(id: String): Unit = {
    println(s"Removing member $id")
  }
}

object Teams {
  private val CsvFile = "teams.csv"

  def data = Csv(CsvFile)
}

object Titles {
  private val CsvFile = "titles.csv"

  def data = Csv(CsvFile)
}

case class Field(index: Int, name: String, multiValue: Boolean, id: Boolean, memberRef: Boolean, teamRef: Boolean, titleRef: Boolean) {
  override def toString = {
    if (multiValue)
      name + " " + Field.MultiValueIndicator
    else if (id)
      name + " " + Field.IdIndicator
    else if (memberRef)
      name + " " + Field.MemberRefIndicator
    else if (teamRef)
      name + " " + Field.TeamRefIndicator
    else if (titleRef)
      name + " " + Field.TitleRefIndicator
    else
      name
  }
}

object Field {
  private val MultiValueIndicator = "(multi)"
  private val IdIndicator = "(id)"
  private val MemberRefIndicator = "(member)"
  private val TeamRefIndicator = "(team)"
  private val TitleRefIndicator = "(title)"

  def apply(index: Int, value: String): Field = {
    val multiValueIndicatorIndex = value.toLowerCase.indexOf(MultiValueIndicator)
    val idIndicatorIndex = value.toLowerCase.indexOf(IdIndicator)
    val memberRefIndicatorIndex = value.toLowerCase.indexOf(MemberRefIndicator)
    val teamRefIndicatorIndex = value.toLowerCase.indexOf(TeamRefIndicator)
    val titleRefIndicatorIndex = value.toLowerCase.indexOf(TitleRefIndicator)

    val name = value.split("(").head.trim

    Field(
      index = index,
      name = name,
      multiValue = multiValueIndicatorIndex != -1,
      id = idIndicatorIndex != -1,
      memberRef = memberRefIndicatorIndex != -1,
      teamRef = teamRefIndicatorIndex != -1,
      titleRef = titleRefIndicatorIndex != -1
    )
  }
}

case class Header(fields: Seq[Field]) {
  // The header should have one and only one id field
  val idField = fields.toList.filter(_.id) match {
    case singleId :: Nil => singleId
    case Nil => sys.error("Header has no id field")
    case multipleIds => sys.error(s"Header has more than one id field [${multipleIds.map(_.name).mkString(",")}")
  }

  val memberRefFields = fields.filter(_.memberRef)
  val teamRefFields = fields.filter(_.teamRef)
  val titleRefFields = fields.filter(_.titleRef)

  def field(fieldName: String): Option[Field] = fields.find { _.name == fieldName }
}

case class Row(index: Int, values: Seq[String]) {
  def value(fieldIndex: Int): String = {
    values.apply(fieldIndex)
  }
}

case class Csv(header: Header, rows: Seq[Row]) {

  // Validate the integrity of the data
  def validate(): Unit = {
    rows.zipWithIndex.foreach {
      case (row, index) =>
        def validateRefs(refFields: Seq[Field], validValues: Seq[String]): Unit = {
          refFields.foreach { refField =>
            val ref = row.value(refField.index)
            require(validValues.contains(ref), s"Invalid ref [$ref] in row [$index]")
          }
        }

        // Validate that all rows have the correct number of fields
        require(row.values.size == header.fields.size, s"Row [$index] does not have the same number of fields as the header")

        // Check the integrity of references
        validateRefs(header.memberRefFields, Members.data.ids)
        validateRefs(header.teamRefFields, Teams.data.ids)
        validateRefs(header.titleRefFields, Titles.data.ids)
    }
  }

  val ids: Seq[String] = rows.map { _.value(header.idField.index) }

  // Returns the row with the given id
  def getRow(id: String): Row = {
    rows.find { _.value(header.idField.index) == id }.getOrElse {
      sys.error(s"Unknown id [$id]")
    }
  }

  def getField(fieldName: String): Field = {
    header.field(fieldName).getOrElse {
      sys.error(s"No such field [$fieldName]. Choose from [${header.fields.map(_.name)}]")
    }
  }

  // Returns the id of all matching rows
  def findRows(fieldName: String, value: String): Seq[String] = {
    val fieldIndex = getField(fieldName).index
    rows.filter { _.value(fieldIndex).contains(value) }.map { _.value(header.idField.index) }
  }

  def addRow(newRow: Seq[String]): Csv = {
    copy(rows = rows :+ Row(rows.size, newRow))
  }

  def updateFieldValue(rowId: String, action: FieldAction, fieldName: String, change: String): Csv = {
    val oldRow = getRow(rowId)
    val field = getField(fieldName)

    val newValue = action.update(oldRow.value(field.index), change)
    val newRowValues = oldRow.values.patch(field.index, Seq(newValue), 1)
    val newRow = oldRow.copy(values = newRowValues)
    val newRows = rows.patch(oldRow.index, Seq(newRow), 1)

    copy(rows = newRows)
  }
}

object Csv {

  private val Separator = ","

  def write(filename: String, csv: Csv): Unit = {
    val header = csv.header.fields.mkString(Separator)
    val rows = csv.rows.map { _.values.mkString(Separator) }

    safelyWrite(filename, header +: rows)
  }

  def apply(filename: String): Csv = {
    safelyRead(filename) { lines =>
      if (lines.hasNext) {
        val headerFields = parseLine(lines.next()).zipWithIndex.map {
          case (value, index) => Field.apply(index, value)
        }
        val header = Header(headerFields)
        val rows = lines.zipWithIndex.map {
          case (line, index) => Row(index, parseLine(line))
        }.toSeq
        Csv(header, rows)
      } else {
        sys.error(s"File [$filename] is missing a header")
      }
    }
  }

  // Closes the file after use
  private def safelyRead[T](filename: String)(f: Iterator[String] => T) = {
    val source = io.Source.fromFile(filename)
    val attemptToProcess = Try { f(source.getLines()) }
    source.close()
    attemptToProcess.get
  }

  // Closes the file after use
  private def safelyWrite(filename: String, rows: Seq[String]): Unit = {
    var bw = new BufferedWriter(new FileWriter(filename, false))
    val attempt = Try {
      rows.foreach { row =>
        bw.write(row)
        bw.newLine()
      }
      bw.flush()
    }

    Try { bw.close() }

    attempt.get
  }

  private def parseLine(line: String): Seq[String] = line.split(",").map(_.trim)
}



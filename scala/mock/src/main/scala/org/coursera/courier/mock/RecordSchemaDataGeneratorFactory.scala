package org.coursera.courier.mock

import com.linkedin.data.DataMap
import com.linkedin.data.schema.ArrayDataSchema
import com.linkedin.data.schema.BooleanDataSchema
import com.linkedin.data.schema.BytesDataSchema
import com.linkedin.data.schema.ComplexDataSchema
import com.linkedin.data.schema.DataSchema
import com.linkedin.data.schema.DoubleDataSchema
import com.linkedin.data.schema.EnumDataSchema
import com.linkedin.data.schema.FixedDataSchema
import com.linkedin.data.schema.FloatDataSchema
import com.linkedin.data.schema.IntegerDataSchema
import com.linkedin.data.schema.LongDataSchema
import com.linkedin.data.schema.MapDataSchema
import com.linkedin.data.schema.NullDataSchema
import com.linkedin.data.schema.PrimitiveDataSchema
import com.linkedin.data.schema.RecordDataSchema.Field
import com.linkedin.data.schema.StringDataSchema
import com.linkedin.data.schema.TyperefDataSchema
import com.linkedin.data.schema.UnionDataSchema
import com.linkedin.data.template.DataTemplateUtil

import collection.JavaConverters._

import com.linkedin.data.schema.RecordDataSchema

private[mock] class RecordSchemaDataGeneratorFactory(
    recordSchema: RecordDataSchema,
    config: RecordSchemaDataGeneratorFactory.Config) {

  import RecordSchemaDataGeneratorFactory._

  /**
   * @return A [[DataMapValueGenerator]] whose generated values conform to `recordSchema`.
   */
  def build(): DataMapValueGenerator = {
    val fieldGenerators: Map[String, ValueGenerator[_ <: AnyRef]] =
      recordSchema.getFields.asScala.flatMap { field =>
        makeFieldGeneratorIfRequired(field).map(field.getName -> _)
      }.toMap
    new DataMapValueGenerator(fieldGenerators)
  }

  /** Builder configuration methods */
  private[this] def makeFieldGeneratorIfRequired(field: Field):
    Option[ValueGenerator[_ <: AnyRef]] = {

    config.fieldGeneratorOverrides.get(field.getName).map(Some(_)).getOrElse {
      if (!config.includeOptionalFields && field.getOptional) {
        None
      } else {
        Some(makeFieldGenerator(field))
      }
    }
  }

  private[this] def makeFieldGenerator(field: Field): ValueGenerator[_ <: AnyRef] = {
    if (field.getDefault != null && config.useSchemaDefaults) {
      new ConstantValueGenerator(field.getDefault)
    } else {
      makeSchemaValueGenerator(field.getName, field.getType)
    }
  }

  private[this] def makeSchemaValueGenerator(
      name: String,
      dataSchema: DataSchema): ValueGenerator[_ <: AnyRef] = {

    makeSchemaReferencedGenerator(dataSchema).getOrElse {

      if (config.requireCustomGeneratorsForCoercedTypes) verifyNoCoercer(dataSchema)

      dataSchema match {
        case schema: PrimitiveDataSchema => makePrimitiveSchemaValueGenerator(name, schema)
        case schema: ComplexDataSchema => makeComplexSchemaDataGenerator(name, schema)
      }
    }
  }

  /**
   * Instantiate the generator class associated with the schema, if defined.
   */
  private[this] def makeSchemaReferencedGenerator(dataSchema: DataSchema):
    Option[ValueGenerator[_ <: AnyRef]] = {

    getGeneratorClassName(dataSchema).map { generatorClassName =>

      Class.forName(generatorClassName).newInstance() match {
        case generator: ValueGenerator[_] => generator
        case other: Any => throw new GeneratorBuilderError(
          s"Expected custom generator with class $generatorClassName to be of type " +
          s"ValueGenerator[AnyRef].")
      }
    }
  }

  /**
   * Verify that there is no coercer associated with the schema.
   */
  private[this] def verifyNoCoercer(dataSchema: DataSchema): Unit = {
    getCoercerClassName(dataSchema).foreach { coercerClassName =>
      val msg =
        s"""Data schema with property @scala.coercerClass = "$coercerClassName" """ +
          s"must define a custom mock generator class @scala.mockGeneratorClass = ??? to " +
          s"ensure that mock data values are comprehensible. \n " +
          s"See [[org.example.common.DateTime]] for an example generator class definition."
      throw GeneratorBuilderError(msg)
    }
  }

  private[this] def makePrimitiveSchemaValueGenerator(
      name: String,
      primitiveSchema: PrimitiveDataSchema): PrimitiveValueGenerator[_ <: AnyRef] = {

    primitiveSchema match {
      case schema: BooleanDataSchema => booleanGenerator()
      case schema: IntegerDataSchema => intGenerator()
      case schema: LongDataSchema => longGenerator()
      case schema: FloatDataSchema => floatGenerator()
      case schema: DoubleDataSchema => doubleGenerator()
      case schema: StringDataSchema => stringGenerator(name)
      case schema: BytesDataSchema => bytesGenerator(name)
      case schema: NullDataSchema =>
        throw GeneratorBuilderError(s"Unsupported schema type ${primitiveSchema.getType} " +
          s"for schema $primitiveSchema.")
    }
  }

  private[this] def makeComplexSchemaDataGenerator(
      name: String,
      complexSchema: ComplexDataSchema): ValueGenerator[_ <: AnyRef] = {

    complexSchema match {
      case schema: EnumDataSchema =>
        new CyclicEnumStringGenerator(schema.getSymbols.asScala.toSet)
      case schema: RecordDataSchema =>
        val builderConfig = config.copy(fieldGeneratorOverrides = Map.empty)
        new RecordSchemaDataGeneratorFactory(schema, builderConfig).build()
      case schema: TyperefDataSchema => makeSchemaValueGenerator(name, schema.getRef)
      case schema: ArrayDataSchema =>
        val itemGenerator = makeSchemaValueGenerator(name, schema.getItems)
        new ListValueGenerator(itemGenerator, config.defaultCollectionLength)
      case schema: MapDataSchema =>
        schema.getUnionMemberKey
        val keyGenerator = makeMapKeyGenerator(name, schema)
        val valueGenerator = makeSchemaValueGenerator(name, schema.getValues)
        new MapValueGenerator(keyGenerator, valueGenerator, config.defaultCollectionLength)
      case schema: UnionDataSchema =>
        val generators = schema.getTypes.asScala.toList.map { memberSchema =>
          new DataMapValueGenerator(Map(
            memberSchema.getUnionMemberKey -> makeSchemaValueGenerator(name, memberSchema)))
        }
        new CyclicGenerator(generators)
      case schema: FixedDataSchema => fixedGenerator(schema.getSize)
    }
  }

  private[this] def makeMapKeyGenerator(
      name: String,
      schema: MapDataSchema): ValueGenerator[_ <: AnyRef] = {

    val stringKeyPrefix = s"${name}_key"

    Option(schema.getProperties.get("keys")).map {
      case "int" => intGenerator()
      case "long" => longGenerator()
      case "float" => floatGenerator()
      case "double" => doubleGenerator()
      case "boolean" => booleanGenerator()
      case "bytes" => bytesGenerator(stringKeyPrefix)
      case data: DataMap => makeSchemaValueGenerator(stringKeyPrefix, dataToDataSchema(data))
      case keyType: String =>
        throw GeneratorBuilderError(
          s"Unsupported map key type `$keyType`. Please define a custom generator for map field " +
          s"'$name' in schema $recordSchema")
    }.getOrElse {
      // `keys` property is absent for string-keyed maps
      stringGenerator(stringKeyPrefix)
    }
  }

  private[this] def intGenerator(): IntegerValueGenerator = new IntegerRangeGenerator()
  private[this] def longGenerator(): LongValueGenerator = new LongRangeGenerator()
  private[this] def floatGenerator(): FloatValueGenerator = new SpanningFloatValueGenerator()
  private[this] def doubleGenerator(): DoubleValueGenerator = new SpanningDoubleValueGenerator()
  private[this] def booleanGenerator(): BooleanValueGenerator = new TrueFalseValueGenerator()
  private[this] def stringGenerator(prefix: String): StringValueGenerator =
    new PrefixedStringGenerator(prefix)

  private[this] def bytesGenerator(prefix: String): BytesValueGenerator =
    new StringBytesValueGenerator(prefix)

  private[this] def fixedGenerator(length: Int): FixedBytesValueGenerator =
    new IntegerRangeFixedBytesGenerator(length)

}

object RecordSchemaDataGeneratorFactory {

  def apply(recordSchema: RecordDataSchema): RecordSchemaDataGeneratorFactory = {
    new RecordSchemaDataGeneratorFactory(recordSchema, Config())
  }

  val SCALA = "scala"
  val COERCER_CLASS_PROPERTY = "coercerClass"
  val MOCK_GENERATOR_CLASS_PROPERTY = "mockGeneratorClass"

  def getCoercerClassName(dataSchema: DataSchema): Option[String] =
    getScalaProperty(dataSchema, COERCER_CLASS_PROPERTY)

  def getGeneratorClassName(dataSchema: DataSchema): Option[String] =
    getScalaProperty(dataSchema, MOCK_GENERATOR_CLASS_PROPERTY)

  case class Config(
      fieldGeneratorOverrides: Map[String, ValueGenerator[_ <: AnyRef]] = Map.empty,
      useSchemaDefaults: Boolean = true,
      includeOptionalFields: Boolean = true,
      defaultCollectionLength: Int = 3,
      requireCustomGeneratorsForCoercedTypes: Boolean = true)

  private[mock] def validGeneratorForField(
      generator: ValueGenerator[_ <: AnyRef], field: Field): Boolean = {

    // TODO amory: Actually check
    true
  }

  private[this] def getScalaProperies(dataSchema: DataSchema): Option[DataMap] = {
    dataSchema.getProperties.asScala.get(SCALA).flatMap {
      case data: DataMap => Some(data)
      case _ => None
    }
  }

  private[this] def getScalaProperty(dataSchema: DataSchema, propertyName: String):
    Option[String] = {
    getScalaProperies(dataSchema).flatMap { properties =>
      Option(properties.getString(propertyName))
    }
  }

  case class GeneratorBuilderError(msg: String) extends IllegalArgumentException(msg)

  def dataToDataSchema(data: DataMap): DataSchema = {
    DataTemplateUtil.parseSchema(dataToJson(data))
  }

  private[this] def dataToJson(data: DataMap): String = {
    val fieldStrings = data.entrySet.asScala.toList.map { entry =>
      val key = entry.getKey
      entry.getValue match {
        case value: String => s""""$key":"$value""""
        case number: Number => s"$key"
        case _ => throw new IllegalAccessException("Unhandled")
      }
    }
    "{" + fieldStrings.mkString(",") + "}"
  }
}

package generator

import com.gilt.apidocgenerator.models._
import lib.{Datatype, DatatypeResolver, Methods, Primitives, Text, Type, TypeKind}
import models.Container

case class ScalaService(
  service: Service,
  orgPackageName: Option[String] = None
) {

  val datatypeResolver = DatatypeResolver(
    enumNames = service.enums.keys.toSet,
    modelNames = service.models.keys.toSet
  )

  val name = ScalaUtil.toClassName(service.name)

  val packageName: String = orgPackageName match {
    case None => ScalaUtil.packageName(service.name)
    case Some(name) => name + "." + ScalaUtil.packageName(service.name)
  }

  // TODO: Make these private and use scalaTypeResolver
  val modelPackageName = s"$packageName.models"
  val enumPackageName = modelPackageName

  def modelClassName(name: String) = modelPackageName + "." + ScalaUtil.toClassName(name)
  def enumClassName(name: String) = enumPackageName + "." + ScalaUtil.toClassName(name)
  // TODO: End make these private

  val models = service.models.map { case (name, model) => (name -> new ScalaModel(this, name, model)) }.toMap

  val enums = service.enums.map { case (name, enum) => (name -> new ScalaEnum(name, enum)) }.toMap

  val packageNamePrivate = packageName.split("\\.").last

  val defaultHeaders: Seq[ScalaHeader] = {
    service.headers.flatMap { h => h.default.map { default => ScalaHeader(h.name, default) } }
  }

  val resources = service.resources.map { case (modelName, resource) =>
    (modelName -> new ScalaResource(this, models(modelName), resource))
  }

  private val scalaTypeResolver = ScalaTypeResolver(
    modelPackageName = modelPackageName,
    enumPackageName = enumPackageName
  )

  def scalaDatatype(
    t: Datatype
  ): ScalaDatatype = {
    scalaTypeResolver.scalaDatatype(t)
  }

}

case class ScalaHeader(name: String, value: String) {
  val quotedValue = s""""$value""""
}


class ScalaModel(val ssd: ScalaService, modelName: String, val model: Model) {

  val name: String = ScalaUtil.toClassName(modelName)

  val plural: String = Text.underscoreAndDashToInitCap(model.plural.getOrElse(Text.pluralize(modelName)))

  val description: Option[String] = model.description

  val fields = model.fields.map { f => new ScalaField(ssd, this.name, f) }.toList

  val argList: Option[String] = ScalaUtil.fieldsToArgList(fields.map(_.definition))

}

class ScalaBody(ssd: ScalaService, val body: Body) {

  val `type`: Datatype = ssd.datatypeResolver.parse(body.`type`).getOrElse {
    sys.error(s"Could not parse type[${body.`type`}] for body[$body]")
  }

  val datatype = ssd.scalaDatatype(`type`)

  val multiple = `type` match {
    case Datatype.Singleton(_) | Datatype.Option(_) => false
    case Datatype.List(_) | Datatype.Map(_) => true
  }

  val name: String = `type`.types match {
    case (single :: Nil) => {
      single match {
        case Type(TypeKind.Primitive, _) => {
          ScalaUtil.toDefaultClassName(multiple = multiple)
        }
        case Type(TypeKind.Model, _) | Type(TypeKind.Enum, _) => {
          ScalaUtil.toClassName(name, multiple = multiple)
        }
      }
    }
    case (multiple) => {
      sys.error("TODO: UNION TYPE")
    }
  }

}

class ScalaEnum(enumName: String, val enum: Enum) {

  val name: String = ScalaUtil.toClassName(enumName)

  val description: Option[String] = enum.description

  val values: Seq[ScalaEnumValue] = enum.values.map { new ScalaEnumValue(_) }

}

class ScalaEnumValue(value: EnumValue) {

  val originalName: String = value.name

  val name: String = ScalaUtil.toClassName(value.name)

  val description: Option[String] = value.description

}

class ScalaResource(ssd: ScalaService, val model: ScalaModel, resource: Resource) {

  val packageName: String = ssd.packageName

  val path = resource.path

  val operations = resource.operations.map { op =>
    new ScalaOperation(ssd, model, op, this)
  }
}

class ScalaOperation(val ssd: ScalaService, model: ScalaModel, operation: Operation, resource: ScalaResource) {

  val method: Method = operation.method

  val path: String = operation.path.getOrElse("")

  val description: Option[String] = operation.description

  val body: Option[ScalaBody] = operation.body.map(new ScalaBody(ssd, _))

  val parameters: List[ScalaParameter] = {
    operation.parameters.toList.map { new ScalaParameter(ssd, _) }
  }

  lazy val pathParameters = parameters.filter { _.location == ParameterLocation.Path }

  lazy val queryParameters = parameters.filter { _.location == ParameterLocation.Query }

  lazy val formParameters = parameters.filter { _.location == ParameterLocation.Form }

  val name: String = GeneratorUtil.urlToMethodName(resource.model.plural, resource.path.getOrElse(""), operation.method, operation.path.getOrElse(""))

  val argList: Option[String] = body match {
    case None => {
      ScalaUtil.fieldsToArgList(parameters.map(_.definition))
    }
    case Some(body) => {
      val varName = ScalaUtil.toVariable(body.`type`)

      Some(
        Seq(
          Some(s"%s: %s".format(ScalaUtil.quoteNameIfKeyword(varName), body.datatype.name)),
          ScalaUtil.fieldsToArgList(parameters.map(_.definition))
        ).flatten.mkString(",")
      )
    }
  }

  private def bodyClassArg(
    name: String,
    multiple: Boolean
  ): String = {
    val baseClassName = ssd.modelClassName(name)
    val className = if (multiple) {
      s"Seq[$baseClassName]"
    } else {
      baseClassName
    }

    Seq(
      Some(s"${ScalaUtil.toVariable(name, multiple)}: $className"),
      ScalaUtil.fieldsToArgList(parameters.map(_.definition))
    ).flatten.mkString(",")
  }

  val responses: Seq[ScalaResponse] = {
    operation.responses.map { case (code, response) => new ScalaResponse(ssd, method, code.toInt, response) }.toSeq
  }

  lazy val resultType = responses.find(_.isSuccess).map(_.resultType).getOrElse("Unit")

}

class ScalaResponse(ssd: ScalaService, method: Method, val code: Int, response: Response) {

  val `type`: Datatype = ssd.datatypeResolver.parse(response.`type`).getOrElse {
    sys.error(s"Could not parse type[${response.`type`}] for response[$response]")
  }

  val isOption = Container(`type`) match {
    case Container.Singleton | Container.Option => !Methods.isJsonDocumentMethod(method.toString)
    case Container.List | Container.Map => false
  }

  val isSuccess = code >= 200 && code < 300
  val isNotFound = code == 404

  val datatype = ssd.scalaDatatype(`type`)

  val isUnit = `type`.types.toList.forall( _ == Type(TypeKind.Primitive, Primitives.Unit.toString) )

  val resultType: String = datatype.name

  val errorVariableName = ScalaUtil.toVariable(`type`)

  val errorClassName = lib.Text.initCap(errorVariableName) + "Response"
}

class ScalaField(ssd: ScalaService, modelName: String, field: Field) {

  def name: String = ScalaUtil.quoteNameIfKeyword(Text.snakeToCamelCase(field.name))

  def originalName: String = field.name

  val `type`: Datatype = ssd.datatypeResolver.parse(field.`type`).getOrElse {
    sys.error(s"Could not parse type[${field.`type`}] for model[$modelName] field[$name]")
  }

  def datatype = ssd.scalaDatatype(`type`)

  def description: Option[String] = field.description

  /**
   * If there is a default, ensure it is only set server side otherwise
   * changing the default would have no impact on deployed clients
   */
  def isOption: Boolean = !field.required.getOrElse(true) || field.default.nonEmpty

  def definition: String = datatype.definition(name, isOption)
}

class ScalaParameter(ssd: ScalaService, param: Parameter) {

  def name: String = ScalaUtil.toVariable(param.name)

  val `type`: Datatype = ssd.datatypeResolver.parse(param.`type`).getOrElse {
    sys.error(s"Could not parse type[${param.`type`}] for param[$param]")
  }

  def originalName: String = param.name

  def datatype = ssd.scalaDatatype(`type`)
  def description: String = param.description.getOrElse(name)

  def default = param.default

  /**
   * If there is a default, ensure it is only set server side otherwise
   * changing the default would have no impact on deployed clients
   */
  def isOption: Boolean = !param.required.getOrElse(true) || param.default.nonEmpty

  def definition: String = datatype.definition(name, isOption)

  def location = param.location
}
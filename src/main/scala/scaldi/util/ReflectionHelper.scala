package scaldi.util

import java.lang.annotation.Annotation
import java.lang.reflect.{Constructor, Field, Method}
import scala.annotation.tailrec
import scala.language.{implicitConversions, postfixOps}
import scala.reflect.internal.{Names, StdNames}
import scala.reflect.runtime.universe.{runtimeMirror, MethodSymbol, Mirror, Symbol, TermName, TermSymbol, Type, TypeTag}
import scala.reflect.runtime.{universe, JavaUniverse}

object ReflectionHelper {
  def getDefaultValueOfParam[T, C](paramName: String)(implicit tt: TypeTag[C]): T = {
    val tpe = tt.tpe

    tpe.members find (_.isConstructor) map (_.asMethod) match {
      case None =>
        throw new IllegalArgumentException(s"Type $tpe has no constructor.")
      case Some(constructor) =>
        constructor.paramLists.headOption.toList.flatten.zipWithIndex
          .find(_._1.name.decodedName.toString == paramName) match {
          case Some((param, idx)) if param.isTerm && param.asTerm.isParamWithDefault =>
            import universe._

            val names      = universe.asInstanceOf[StdNames with Names]
            val name       = names.nme.defaultGetterName(names.nme.CONSTRUCTOR, idx + 1).encodedName.toString
            val reflection = mirror.reflect(mirror.reflectModule(tpe.typeSymbol.companion.asModule).instance)

            reflection.reflectMethod(tpe.companion.member(TermName(name)).asMethod).apply().asInstanceOf[T]
          case _ =>
            throw new IllegalArgumentException(
              s"Can't find constructor argument $paramName with default value. Note, that only the first argument list is supported."
            )
        }
    }
  }

  def mirror: Mirror = {
    val classLoader =
      if (Thread.currentThread.getContextClassLoader != null)
        Thread.currentThread.getContextClassLoader
      else this.getClass.getClassLoader

    runtimeMirror(classLoader)
  }

  def overrides(method: Symbol): Seq[Symbol] = {
    val origPackage = getPackage(method)
    method.overrides.filter(o => o.isPublic || o.isProtected || (!o.isPrivate && getPackage(o) == origPackage))
  }

  def classToType(clazz: Class[_]): Type =
    mirror.classSymbol(clazz).toType

  @tailrec
  private def getPackage(s: Symbol): Symbol = if (s.isPackage) s else getPackage(s.owner)

  def hasAnnotation[T <: Annotation: TypeTag](a: Annotation): Boolean =
    hasAnnotation[T](classToType(a.getClass))

  def hasAnnotation[T <: Annotation: TypeTag](t: Type): Boolean = {
    val expectedTpe = implicitly[TypeTag[T]].tpe
    t.baseClasses flatMap (_.annotations) exists (_.tree.tpe =:= expectedTpe)
  }

  // Dirty tricks to compensate for scala reflection API missing feature or bugs

  private def undo[T](block: => T): T =
    scala.reflect.runtime.universe match {
      case ju: JavaUniverse => ju.undoLog.undo(block)
      case _                => block
    }

  // Workaround for https://issues.scala-lang.org/browse/SI-9177
  // TODO: get rid of this workaround as soon as https://issues.scala-lang.org/browse/SI-9177 is resolved!
  // <:< memory leak explained in https://github.com/scala/bug/issues/8302
  /** Check assignability with additional undoLog memory leak check and avoid 'Illegal cyclic reference'. */
  def isAssignableFrom(a: Type, b: Type): Boolean =
    try undo(b <:< a)
    catch {
      case e: Throwable if e.getMessage != null && e.getMessage.contains("illegal cyclic reference") =>
        false
    }

  /** Dirty little trick to convert java constructor to scala constructor. (the reason for it is that scala reflection
    * does not list private constructors)
    */
  def constructorSymbol(c: Constructor[_]): MethodSymbol = {
    val mirror = ReflectionHelper.mirror
    val constructorConverter =
      mirror.classSymbol(mirror.getClass).typeSignature.member(TermName("jconstrAsScala")).asMethod

    mirror.reflect(mirror: AnyRef).reflectMethod(constructorConverter).apply(c).asInstanceOf[MethodSymbol]
  }

  /** Dirty little trick to convert get method argument annotations. (the reason for it is that scala reflection does
    * not give this information)
    */
  def methodParamsAnnotations(method: MethodSymbol): (List[Annotation], List[List[Annotation]]) = {
    val mirror       = ReflectionHelper.mirror
    val methodToJava = mirror.classSymbol(mirror.getClass).typeSignature.member(TermName("methodToJava")).asMethod
    val jmethod      = mirror.reflect(mirror: AnyRef).reflectMethod(methodToJava).apply(method).asInstanceOf[Method]

    jmethod.getAnnotations.toList -> jmethod.getParameterAnnotations.toList.map(_.toList)
  }

  def fieldAnnotations(field: TermSymbol): Seq[Annotation] = {
    val mirror      = ReflectionHelper.mirror
    val fieldToJava = mirror.classSymbol(mirror.getClass).typeSignature.member(TermName("fieldToJava")).asMethod
    val jfield      = mirror.reflect(mirror: AnyRef).reflectMethod(fieldToJava).apply(field).asInstanceOf[Field]

    jfield.getAnnotations.toList
  }

  implicit class SafelyAssignable(val t: Type) extends AnyVal {

    /** Alias for [[isAssignableFrom()]] */
    def safe_<:<(that: Type): Boolean = isAssignableFrom(that, t)
  }
}

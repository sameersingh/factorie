package cc.factorie

trait BooleanValue extends TypedCategoricalValue[Boolean] {
  type VariableType <: BooleanValue
  override def value = (intValue == 1) // Efficiently avoid a lookup in the domain 
  def booleanValue: Boolean = (intValue == 1)
  def ^(other:BooleanValue):Boolean = value && other.value
  def v(other:BooleanValue):Boolean = value || other.value
  def ==>(other:BooleanValue):Boolean = !value || other.value
  override def toString = if (intValue == 0) printName+"(false)" else printName+"(true)"
  type DomainType <: BooleanDomain[VariableType]
  class DomainClass extends BooleanDomain
  // Domain is not in subclasses:  all BooleanValue variables share the same domain.
}

/** A trait for mutable Boolean variables. */
trait BooleanVariable extends BooleanValue with TypedCategoricalVariable[Boolean] { 
  type VariableType <: BooleanVariable
}

class BooleanObservation(b:Boolean) extends BooleanValue {
  final val index = if (b) 1 else 0 // matches mapping from Boolean=>Int in BooleanDomain 
}

// The next two are versions that take convenient constructor arguments.

/** A variable class for boolean values, defined specially for convenience.  
    If you have several different "types" of booleans, you might want to subclass this to enable type safety checks.
    This class allowed variable-value coordination by overriding the 'setByIndex' method; by contrast the 'Bool' class does not. */
class CoordinatedBoolVariable(initialValue: Boolean) extends BooleanVariable {
  def this() = this(false)
  type VariableType <: CoordinatedBoolVariable
  setByIndex(if (initialValue == true) 1 else 0)(null)
}

/** A variable class for boolean values, defined specially for convenience.  
    If you have several different "types" of booleans, you might want to subclass this to enable type safety checks. */
// TODO Should I rename this BoolVariable for consistency?
class BoolVariable(b: Boolean) extends CoordinatedBoolVariable(b) with UncoordinatedCategoricalVariable {
  def this() = this(false)
  type VariableType <: BoolVariable
}

// Provide an alias to the old name for now
class Bool(b:Boolean) extends BoolVariable(b) {
  def this() = this(false)
}
class CoordinatedBool(b:Boolean) extends CoordinatedBoolVariable(b) {
  def this() = this(false)
}


class BooleanDomain[V<:Bool] extends CategoricalDomain[V] {
  this += false // Make sure we initialize the domain contents in this order to that false==0 and true==1
  this += true
  this.freeze
}

object Bool {
  val t = new Bool(true)
  val f = new Bool(false)
  def apply(b: Boolean) = if (b) t else f
}

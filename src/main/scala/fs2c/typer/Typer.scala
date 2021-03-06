package fs2c.typer

import fs2c.io.{SourcePosSpan, Positional}
import fs2c.ast.Symbol
import fs2c.ast.Scopes._
import fs2c.ast.fs._
import Trees.{LocalDef, Typed, Untyped, tpd, untpd, ExprBinOpType => bop, ExprUnaryOpType => uop}
import Types._
import GroundType._
import Constraints._

import fs2c.tools.Unique
import Unique.{freshTypeVar, uniqueName}

/** Typer for Featherweight Scala.
  */
class Typer {
  import Typer.TypeError

  /** Records all typed trees in the scope for the ease of force instantation of all type variables
    * 
    * @param allTyped All typed trees in the current scope.
    * @param parent Parent of the current scope.
    */
  case class TypingScope(var allTyped: List[Typed[_]], parent: TypingScope)

  /** Scoped symbol table.
    */
  val scopeCtx: ScopeContext = new ScopeContext

  /** Constraint solver for type variables.
    */
  val constrs = new ConstraintSolver

  /** Solved substitution of type variables.
    */
  private def solvedSubst: Substitution = constrs.solve

  /** Records all expressions that have been typed.
    */
  var typingScope: TypingScope = TypingScope(Nil, null)

  /** Returns all typed ASTs in the current block.
    */
  def typedInBlock: List[Typed[_]] = typingScope.allTyped

  /** Forcefully instantiate all type variables in the current block.
    */
  def forceInstantiateBlock(): Unit =
    typedInBlock foreach { tpd => forceInstantiate(tpd) }

  /** Strip all class type variables in the current block.
    */
  def stripClassTvarBlock(): Unit =
    typedInBlock foreach { tpd =>
      tpd.tpe = stripClassTypeVariable(tpd.tpe)
    }

  /** Record typed tree in the current block.
    */
  def recordTyped[X](tpd: Typed[X]): Typed[X] = {
    typingScope.allTyped = tpd :: typingScope.allTyped
    tpd
  }

  /** Locate a fresh typing scope.
    */
  def locateTypingScope(): Unit =
    typingScope = TypingScope(Nil, typingScope)

  /** Relocate to previous typing scope.
    */
  def relocateTypingScope(): Unit = {
    assert(typingScope.parent ne null, "can not relocate from the root typing scope.")
    typingScope = typingScope.parent
  }

  /** Alias of [[ConstraintSolver.addEquality]].
    */
  def recordEquality(tpe1: Type, tpe2: Type, pos: SourcePosSpan, lhs: Option[SourcePosSpan], rhs: Option[SourcePosSpan]): Unit = {
    constrs.addEquality(tpe1, tpe2, pos, lhs, rhs)
  }

  /** Fresh type variable prefixed with T.
    */
  def freshTvar(pos: Positional): TypeVariable =
    freshTypeVar(prefix = "T").withPos(pos)

  /** Fresh type variable prefixed with X.
    */
  def freshXvar(pos: Positional): TypeVariable =
    freshTypeVar(prefix = "X").withPos(pos)

  /** Creates a fresh class type variable for `clsDef`.
    */
  def clsVar(clsDef: tpd.ClassDef): ClassTypeVariable =
    ClassTypeVariable(clsDef, Nil)

  /** Perform force instantiation of the type.
    * Will throw a error if there are type variable that can not be
    * instantiated in the type.
    */
  def forceInstantiate[X](tpd: Typed[X]): Typed[X] = {
    val instType = solvedSubst.instantiateType(tpd.tpe)
    instType match {
      case Left(tv) =>
        throw TypeError(s"can not instantiate type variable $tv, which is created\n${tv.showInSourceLine("here")}").withPos(tpd)
      case Right(tpe) =>
        tpd.tpe = tpe
        tpd
    }
  }

  /** Tries to instantiate the type. Returns [[None]] if there exists uninstantiated type variable within the type.
    */
  def tryInstantiateType(tpe: Type): Option[Type] =
    solvedSubst.instantiateType(tpe) match {
      case Left(_) => None
      case Right(x) => Some(x)
    }

  def maybeInstantiateType(tp: Type): Type =
    tryInstantiateType(tp) getOrElse tp

  /** Forcefully instantiate the type. Throws an error if fails.
    */
  def forceInstantiateType(tpe: Type): Type =
    tryInstantiateType(tpe) match {
      case None =>
        throw TypeError(s"can not instantiate type variable $tpe")
      case Some(t) => t
    }

  /** Instantiate class type variable. Fails if the class definition does not conform to the predicates.
    */
  def instantiateClassTypeVariable(clsVar: ClassTypeVariable): Type = {
    import Predicate._
    val expectTypeList: List[(String, Type)] =
      clsVar.predicates map { case HaveMemberOfType(mem, t) => mem -> forceInstantiateType(t) }
    val expectTypes: Map[String, Type] =
      expectTypeList.groupMapReduce(_._1)(_._2)((tpe1, tpe2) =>
        if tpe1 == tpe2 then
          tpe1
        else
          throw TypeError(s"mismatch of class type member: $tpe1 and $tpe2")
      )
    val realTypes: Map[String, Type] =
      (clsVar.classDef.tree.members map { x => x.tree.sym.name -> forceInstantiate(x).tpe }).toMap

    if !(expectTypes.keySet subsetOf realTypes.keySet) then
      throw TypeError(s"can not conform required member set ${expectTypes.keySet} with actual set ${realTypes.keySet}")

    expectTypes.keys map { key =>
      val expect : Type = expectTypes(key)
      val real : Type = realTypes(key)
      if expect != real then
        throw TypeError(s"unmatched type for member $key, expected $expect but found $real")
    }

    clsVar.classDef.tree
  }

  /** Strip class type variable of within the type.
    */
  def stripClassTypeVariable(tpe: Type): Type = tpe match {
    case clv : ClassTypeVariable => clv.classDef.tree
    case LambdaType(params, ret) => LambdaType(params map stripClassTypeVariable, stripClassTypeVariable(ret))
    case ArrayType(item) => ArrayType(stripClassTypeVariable(item))
    case t => t
  }

  /** Type class definitions.
    */
  def typedClassDef(classDef: untpd.ClassDef): tpd.ClassDef = {
    val clsDef: Trees.ClassDef[Untyped] = classDef.tree

    // tpd.ClassDef <--> ClassTypeVariable
    val typedClsDef: tpd.ClassDef = clsDef.assignType(null, null).withPos(classDef.pos)
    val clsTvar: ClassTypeVariable = clsVar(typedClsDef)
    typedClsDef.tpe = clsTvar
    
    // add class definition into scope
    scopeCtx.addSymbol(typedClsDef.tree.sym)
    
    // add constructor parameters into scope
    clsDef.params foreach { sym => typedLambdaParam(sym.dealias, classDef) }
    clsDef.params foreach { sym => scopeCtx.addSymbol(sym) }

    // --- locate a new scope ---
    scopeCtx.locateScope()
    locateTypingScope()

    // introduce placeholder definitions into scope
    val untypedMemDefs: List[Trees.MemberDef[Untyped]] = clsDef.members map (_.tree)
    var placeholders: Map[String, Symbol[tpd.MemberDef]] = Map.empty
    val preTypedMemberDefs: List[tpd.MemberDef] = untypedMemDefs map { memDef =>
      val tpe: Type = tryResolveSymbolType(memDef.tpe getOrElse freshXvar(memDef.sym), memDef.sym.dealias)
      val typed: tpd.MemberDef = memDef.assignType(tpe, typedClsDef.tree.sym, null)
      scopeCtx.addSymbol(typed.tree.sym)
      placeholders = placeholders.updated(typed.tree.sym.name, typed.tree.sym)
      typed
    }
    typedClsDef.tree.members = preTypedMemberDefs

    // typing the member definitions
    val typedMemDefs: List[tpd.MemberDef] = clsDef.members map { d =>
      val untpdD = d
      val res = typedMemberDef(d)
      
      res match {
        case tpdDef @ Typed(d : Trees.MemberDef[Typed], actualType, _, _) =>
          placeholders get d.sym.name match {
            case None =>
              assert(false, s"fatal error: member name not found in placeholders. This is caused by a bug.")
            case Some(sym) =>
              val assumedType: Type = sym.dealias.tpe
              recordEquality(assumedType, actualType, untpdD.pos, Some(untpdD.tree.sym.pos), Some(untpdD.tree.body.pos))
              sym.dealias = tpdDef
              tpdDef.tree.classDef = typedClsDef.tree.sym
          }
      }
      
      res
    }

    /** forcefully instantiate all type variables and strip class
      * note: this will not instantiate the currently typing ClassDef */
    forceInstantiateBlock()

    typedClsDef.tree.members = typedMemDefs

    // instantiate the class type
    typedClsDef.tpe = instantiateClassTypeVariable(clsTvar)
    // strip all class type variables in the block
    stripClassTvarBlock()


    // --- relocate to the old scope ---
    scopeCtx.relocateScope()
    relocateTypingScope()

    typedClsDef
  }

  /** Type member definitions.
    *
    * Simply compute the type of the body. Will not check whether ascription and actual type matchs (this will be done
    * within [[Typer.typedClassDef]]. Will not add symbol into the scope.
    */
  def typedMemberDef(memberDef: untpd.MemberDef): tpd.MemberDef = recordTyped {
    val memDef: Trees.MemberDef[Untyped] = memberDef.tree

    val body: tpd.Expr = typedExpr(memDef.body)
    val tpe: Type = body.tpe

    memDef.assignType(tpe, null, body).withPos(memberDef)
  }


  /** Type expressions.
    */
  def typedExpr(expr: untpd.Expr): tpd.Expr = recordTyped {
    val res = expr.tree match {
      case x : Trees.LiteralUnitExpr[_] => x.typed
      case x : Trees.LiteralIntExpr[_] => x.assignType(IntType)
      case x : Trees.LiteralFloatExpr[_] => x.assignType(FloatType)
      case x : Trees.LiteralBooleanExpr[_] => x.assignType(BooleanType)
      case x : Trees.LiteralStringExpr[_] => x.assignType(StringType)
      case x : Trees.LiteralArrayExpr[Untyped] =>
        val tpdLength = typedExpr(x.length)
        recordEquality(tpdLength.tpe, GroundType.IntType, expr.pos, Some(x.length.pos), None)
        x.assignType(tpdLength)
      case x : Trees.BinOpExpr[Untyped] => typedBinOpExpr(expr.asInstanceOf)
      case x : Trees.UnaryOpExpr[Untyped] => typedUnaryOpExpr(expr.asInstanceOf)
      case x : Trees.LambdaExpr[Untyped] => typedLambdaExpr(expr.asInstanceOf)
      case x : Trees.IdentifierExpr[Untyped] => typedIdentifierExpr(expr.asInstanceOf)
      case x : Trees.BlockExpr[Untyped] => typedRecBlockExpr(expr.asInstanceOf)
      case x : Trees.ApplyExpr[Untyped] => typedApplyExpr(expr.asInstanceOf)
      case x : Trees.IfExpr[Untyped] => typedIfExpr(expr.asInstanceOf)
      case x : Trees.NewExpr[Untyped] => typedNewExpr(expr.asInstanceOf)
      case x : Trees.SelectExpr[Untyped] => typedSelectExpr(expr.asInstanceOf)
      case x : Trees.GroundValue[Untyped] => typedGroundValue(expr.asInstanceOf)
      case x : Trees.Printf[Untyped] => typedPrintfExpr(expr.asInstanceOf)
    }

    /** inherit position in typed expressions */
    res.withPos(expr)
  }

  def typedPrintfExpr(expr: untpd.Printf): tpd.Printf = expr.tree match {
    case t @ Trees.Printf(fmt, params, strValue) =>
      val tpdParams: List[tpd.Expr] = params map typedExpr
      t.assignType(tpdParams, if strValue then GroundType.StringType else GroundType.IntType)
  }

  def typedGroundValue(expr: untpd.GroundValue): tpd.GroundValue = expr.tree.typed.withPos(expr)

  /** Type lambda expressions.
    *
    * ```text
    * Γ, x1 : T1, ..., xn : Tn |- t : T | C
    * ----------------------------------------------------------
    * Γ |- (x1 : T1, ..., xn : Tn) => t : (T1, ..., Tn) => T | C
    * ```
    */
  def typedLambdaExpr(expr: untpd.LambdaExpr): tpd.LambdaExpr = {
    val lambda: Trees.LambdaExpr[Untyped] = expr.tree

    // --- Open a new scope ---
    scopeCtx.locateScope()

    // add lambda parameters into the scope
    lambda.params foreach { sym => typedLambdaParam(sym.dealias, expr) }
    lambda.params map { sym => scopeCtx.addSymbol(sym) }
    // typing the body
    val typedBody: tpd.Expr = typedExpr(lambda.body)
    lambda.tpe match {
      case None => ()
      case Some(tpe) =>
        if tpe != typedBody.tpe then
          throw TypeError(s"Expected lambda body type: $tpe, given: ${typedBody.tpe}").withPos(expr)
        else
          ()
    }

    // --- Close the scope ---
    scopeCtx.relocateScope()

    lambda.assignType(
      tpe = LambdaType(lambda.params map { sym => sym.dealias.tpe }, typedBody.tpe),
      body = typedBody
    )
  }

  /** Type block expressions.
    */
  def typedBlockExpr(expr: untpd.BlockExpr): tpd.BlockExpr = {
    val block: Trees.BlockExpr[Untyped] = expr.tree

    // --- Open a new scope ---
    scopeCtx.locateScope()

    val defs: List[tpd.LocalDef] = block.defs map { d => typedLocalDef(d, recursiveMode = false) }
    val tpdExpr: tpd.Expr = typedExpr(block.expr)

    // --- Close the scope ---
    scopeCtx.relocateScope()

    block.assignType(tpdExpr.tpe, defs, tpdExpr)
  }

  /** Type block expression with recursive definition group.
    *
    * ```text
    * Γ, x1 : X1, ..., xn : Xn |- t1 : T1 | C1
    * ...
    * Γ, x1 : X1, ..., xn : Xn |- tn : Tn | Cn
    * Γ, x1 : X1, ..., xn : Xn |- t : T | C
    * C' = C \/ C1 \/ ... \/ Cn \/ { X1 = T1, ..., Xn = Tn }
    * Xi is the ascripted type of xi if exists, a fresh type
    * variable otherwise
    * ------------------------------------------------------
    * Γ |- { val x1 = t1; ...; val xn = tn; t } : T | C'
    * ```
    */
  def typedRecBlockExpr(expr: untpd.BlockExpr): tpd.BlockExpr = {
    val block: Trees.BlockExpr[Untyped] = expr.tree

    // --- locate a new scope ---
    scopeCtx.locateScope()
    locateTypingScope()
    val untpdDefs: List[Trees.LocalDef[Untyped]] = block.defs map (_.tree)
    var placeholders: Map[String, Symbol[tpd.LocalDefBind]] = Map.empty

    untpdDefs foreach {
      case d @ Trees.LocalDef.Bind(Symbol(symName, _), mutable, ascription, _) =>
        // type of the binding
        val tpe: Type = ascription getOrElse freshXvar(d.sym)
        val tpdBind: tpd.LocalDefBind = Typed(tpe = tpe, tree = Trees.LocalDef.Bind(null, mutable, null, null))
        val sym: Symbol[tpd.LocalDefBind] = Symbol(name = symName, dealias = tpdBind)
        scopeCtx.addSymbol(sym)
        placeholders = placeholders.updated(symName, sym)
      case _ =>
    }

    val tpdDefs: List[tpd.LocalDef] = block.defs map { d => 
      val res = typedLocalDef(d, recursiveMode = true)
      
      res match {
        case res @ Typed(bind : Trees.LocalDef.Bind[Typed], actualType, _, _) =>
          val symName = bind.sym.name
          placeholders.get(symName) match {
            case None =>
            case Some(wrapper) =>
              val assumedType = wrapper.dealias.tpe
              wrapper.dealias = res.asInstanceOf
              recordEquality(assumedType, actualType, d.pos, Some(bind.sym.pos), Some(bind.body.pos))
          }
        case _ =>
      }

      /** Record position in local definitions */
      res.withPos(d)
    }

    // instantiate all type variables in the block
    // if the instantiation fails, the recursive definitions types can not be inferred
    forceInstantiateBlock()

    val tpdExpr: tpd.Expr = typedExpr(block.expr)

    // --- relocate to previous scope ---
    scopeCtx.relocateScope()
    relocateTypingScope()

    forceInstantiate { block.assignType(tpdExpr.tpe, tpdDefs, tpdExpr) }
  }

  /** Types local definition.
    */
  def typedLocalDef(expr: untpd.LocalDef, recursiveMode: Boolean = true): tpd.LocalDef = recordTyped {
    def checkAssignFree(sym: Symbol[_])(tpdAssign: tpd.LocalDefAssign): tpd.LocalDefAssign = {
      val isFree = scopeCtx.findSymHere(sym.name).isEmpty
      if isFree then
        tpdAssign.freeNames = sym :: tpdAssign.freeNames
      
      tpdAssign
    }

    val untpdExpr: untpd.LocalDef = expr
    
    val localDef: Trees.LocalDef[Untyped] = expr.tree

    localDef match {
      case bind @ Trees.LocalDef.Bind(_, _, tpe, body) =>
        val typedBody = typedExpr(body)
        tpe match {
          case Some(tpe) if !recursiveMode && tpe != typedBody.tpe =>
            throw TypeError(s"Type mismatch: expecting $tpe but found ${typedBody.tpe}").withPos(expr)
          case _ => ()
        }
        val typedBind: tpd.LocalDefBind = bind.assignTypeBind(typedBody)
        if !recursiveMode then
          scopeCtx.addSymbol(typedBind.tree.sym)

        typedBind
      case eval @ Trees.LocalDef.Eval(expr) =>
        eval.assignTypeEval(typedExpr(expr))
      case loop @ Trees.LocalDef.While(cond, body) =>
        loop.assignTypeWhile(typedExpr(cond), typedExpr(body))
      case assign @ Trees.LocalDef.AssignRef(ref, expr) =>
        val assignee: tpd.Expr = typedExpr(ref)
        if !assignee.tpe.isRef then
          throw TypeError(s"can not assign to a non-reference expr: $assignee")
        val tpdExpr: tpd.Expr = typedExpr(expr)
        recordEquality(assignee.tpe, tpdExpr.tpe, untpdExpr.pos, Some(ref.pos), Some(expr.pos))
        assign.assignTypeAssignRef(assignee, tpdExpr)
      case assign @ Trees.LocalDef.Assign(symRef, expr) => {
        val sym: Symbol[_] = symRef match {
          case Symbol.Ref.Resolved(sym) =>
            scopeCtx.findSym(sym.name) match {
              case None =>
                throw TypeError(s"unknown symbol: ${sym.name}").withPos(untpdExpr)
              case Some(sym) => sym
            }
          case Symbol.Ref.Unresolved(symName) =>
            scopeCtx.findSym(symName) match {
              case None =>
                throw TypeError(s"unknown symbol: $symName").withPos(untpdExpr)
              case Some(sym) => sym
            }
        }
        val tpdExpr: tpd.Expr = typedExpr(expr)
        checkAssignFree(sym) {
          if isSymbolMutable(sym) && tpdExpr.tpe == typeOfSymbol(sym) then
            assign.assignTypeAssign(sym, tpdExpr)
          else
            if !isSymbolMutable(sym) then
              throw TypeError(s"can not assign to an immutable $sym").withPos(untpdExpr)
            else if !recursiveMode then
              throw TypeError(s"can not assign value of ${tpdExpr.tpe} to $sym").withPos(untpdExpr)
            else
              recordEquality(tpdExpr.tpe, typeOfSymbol(sym), untpdExpr.pos, Some(expr.pos), None)
              assign.assignTypeAssign(sym, tpdExpr)
        }
      }
    }
  }

  /** Checks whether a symbol is mutable.
    */
  def isSymbolMutable(sym: Symbol[_]): Boolean = {
    sym.dealias match {
      case typed : Typed[_] =>
        val tree = typed.tree
        tree match {
          case bind : Trees.LocalDef.Bind[_] => bind.mutable
          case member : Trees.MemberDef[_] => member.mutable
          case _ => false
        }
      case _ => false
    }
  }

  /** Retrieve the type of the symbol.
    */
  def typeOfSymbol(sym: Symbol[_]): Type =
    val tp = sym.dealias match {
      case tped : Typed[_] => tped.tpe
      case param : Trees.LambdaParam => param.tpe
      case _ =>
        assert(false, s"can not retrieve type of symbol $sym with dealias ${sym.dealias}")
    }
    if isSymbolMutable(sym) then
      tp.refType
    else
      tp

  /** Typing lambda parameters. This will resolve SymbolType if exists.
    */
  def typedLambdaParam(param: Trees.LambdaParam, e: Untyped[_]): Trees.LambdaParam = {
    param.tpe match {
      case symTp : SymbolType =>
        param.tpe = resolveSymbolType(symTp, e)
      case _ =>
    }
    param
  }

  def tryResolveSymbolType(tp: Type, e: Untyped[_]): Type = tp match {
    case tp: SymbolType => resolveSymbolType(tp, e)
    case LambdaType(paramTypes, valueType) => LambdaType(paramTypes map { t => tryResolveSymbolType(t, e) }, tryResolveSymbolType(valueType, e))
    case GroundType.ArrayType(tp) => GroundType.ArrayType(tryResolveSymbolType(tp, e))
    case tp => tp
  }

  /** Resolves the symbol type.
    */
  def resolveSymbolType(tpe: SymbolType, e: Untyped[_]): Type = {
    val symName = tpe.refSym.name
    scopeCtx.findSym(symName) match {
      case Some(sym) => 
        typeOfSymbol(sym) match {
          case clv : ClassTypeVariable => clv
          case cls : Trees.ClassDef[Typed] => cls
        }
      case None =>
        throw TypeError(s"unknown symbol: $symName").withPos(e)
    }
  }

  /** Type identifiers.
    *
    * ```
    * x : T ∈ Γ
    * ----------
    * Γ |- x : T
    * ```
    */
  def typedIdentifierExpr(expr: untpd.IdentifierExpr): tpd.IdentifierExpr = {
    def setFreshName(sym: Symbol[_])(identExpr: tpd.IdentifierExpr): tpd.IdentifierExpr = {
      def foundInCurrentScope(sym: Symbol[_]): Boolean =
        scopeCtx.findSymHere(sym.name).isDefined
      
      if !foundInCurrentScope(sym) then
        identExpr.freeNames = List(sym)

      identExpr
    }

    expr.tree.sym match {
      case Symbol.Ref.Unresolved(symName) =>
        scopeCtx.findSym(symName) match {
          case None =>
            throw TypeError(s"unknown symbol: $symName").withPos(expr)
          case Some(sym) =>
            setFreshName(sym) {
              expr.tree.assignType(typeOfSymbol(sym), sym)
            }
        }
      case Symbol.Ref.Resolved(sym) =>
        scopeCtx.findSym(sym.name) match {
          case None =>
            throw TypeError(s"unknown resolved symbol: ${sym.name}, this is caused by a bug in the compiler.").withPos(expr)
          case Some(sym) =>
            setFreshName(sym) {
              expr.tree.assignType(typeOfSymbol(sym), sym)
            }
        }
    }
  }

  /** Type application expression.
    *
    * ```text
    * Γ |- t1 : T1 | C1
    * Γ |- t2 : T2 | C2
    * X is a fresh type variable
    * C' = C1 /\ C2 /\ { T1 = T2 -> X }
    * ---------------------------------
    *        Γ |- t1 t2 : X | C'
    * ```
    */
  def typedApplyExpr(expr: untpd.ApplyExpr): tpd.ApplyExpr = {
    def apply = expr.tree
    val func: tpd.Expr = typedExpr(apply.func)
    val params: List[tpd.Expr] = apply.args map typedExpr
    val paramTypes: List[Type] = params map (_.tpe)

    maybeInstantiateType(func.tpe) match {
      case ArrayType(elemTp) =>
        paramTypes match {
          case IntType :: Nil =>
            apply.assignType(tpe = elemTp.refType, func, params)
          case t :: Nil =>
            recordEquality(t, IntType, expr.pos, Some(apply.args.head.pos), None)
            apply.assignType(tpe = elemTp.refType, func, params)
          case _ =>
            throw TypeError(s"expecting one Int to index an Array").withPos(expr)
        }
      case LambdaType(expectParamTypes, vTp) =>
        val valueType = tryResolveSymbolType(vTp, apply.func)
        @annotation.tailrec def go(ts1: List[Type], ts2: List[Type]): Unit = (ts1, ts2) match {
          case (Nil, Nil) => ()
          case (Nil, _) | (_, Nil) =>
            throw TypeError(s"parameter number mismatch.").withPos(expr)
          case ((t1: SymbolType) :: ts1, t2 :: ts2) if resolveSymbolType(t1, apply.func) == t2 =>
            go(ts1, ts2)
          case (t1 :: ts1, (t2: SymbolType) :: ts2) if t1 == resolveSymbolType(t2, apply.func) =>
            go(ts1, ts2)
          case (t1 :: ts1, t2 :: ts2) if t1 == t2 =>
            go(ts1, ts2)
          case (t1 :: ts1, t2 :: ts2) =>
            recordEquality(t1, t2, expr.pos, None, None)
//            throw TypeError(s"arugment type mismatch: $t1 and $t2").withPos(expr)
        }
        // do arugment type checking
        go(expectParamTypes, paramTypes)
        apply.assignType(tpe = valueType, func, params)
      case tpe: Type =>
        val retType = freshTvar(expr)
        val expectType = LambdaType(paramTypes, retType)
        recordEquality(tpe, expectType, expr.pos, None, None)
        apply.assignType(tpe = retType, func, params)
    }
  }

  /** Type if expressions.
    *
    * ```
    * Γ |- t1 : T1 | C1, t2 : T2 | C2, t3 : T3 | C3
    * C' = C1 \/ C2 \/ C3 \/ { T1 = Boolean, T2 = T3 }
    * ------------------------------------------------
    *      Γ |- if t1 then t2 else t3 : T2 | C'
    * ```
    */
  def typedIfExpr(expr: untpd.IfExpr): tpd.IfExpr = {
    val ifExpr: Trees.IfExpr[Untyped] = expr.tree
    val tpdCond: tpd.Expr = typedExpr(ifExpr.cond)
    val tpdTBody: tpd.Expr = typedExpr(ifExpr.trueBody)
    val tpdFBody: tpd.Expr = typedExpr(ifExpr.falseBody)

    val condType: Type = tryInstantiateType(tpdCond.tpe) match {
      case None =>
        recordEquality(tpdCond.tpe, GroundType.BooleanType, ifExpr.cond.pos, None, None)
        tpdCond.tpe
      case Some(tpe) if tpe == GroundType.BooleanType =>
        tpe
      case Some(tpe) =>
        throw TypeError(s"expect Boolean in if condition, but found $tpe").withPos(ifExpr.cond)
    }

    val (tpeT, tpeF) = (tryInstantiateType(tpdTBody.tpe), tryInstantiateType(tpdFBody.tpe)) match {
      case (Some(tpe1), Some(tpe2)) if tpe1 == tpe2 => (tpe1, tpe2)
      case (Some(tpe1), Some(tpe2)) =>
        throw TypeError(s"body of if expressions have different types: $tpe1 and $tpe2").withPos(expr)
      case (_, _) =>
        val (tpe1, tpe2) = (tpdTBody.tpe, tpdFBody.tpe)
        recordEquality(tpe1, tpe2, expr.pos, Some(ifExpr.trueBody.pos), Some(ifExpr.falseBody.pos))
        (tpe1, tpe2)
    }

    // instantiate condition and body types if possible
    tpdCond.tpe = condType
    tpdTBody.tpe = tpeT
    tpdFBody.tpe = tpeF

    ifExpr.assignType(tpeT, tpdCond, tpdTBody, tpdFBody)
  }

  /** Typing new expressions.
    */
  def typedNewExpr(expr: untpd.NewExpr): tpd.NewExpr = {
    val newExpr: Trees.NewExpr[Untyped] = expr.tree
    val clsName = newExpr.ref.name
    val clsSym = scopeCtx.findSym(clsName) match {
      case None =>
        throw TypeError(s"unknown symbol $clsName").withPos(expr)
      case Some(sym) =>
        sym
    }
    val clsTpe : Type = typeOfSymbol(clsSym) match {
      case clv : ClassTypeVariable => clv
      case clsDef : Trees.ClassDef[_] => clsDef
      case x =>
        throw TypeError(s"$x is not a class").withPos(expr)
    }
    val innerClsTpe: Trees.ClassDef[Typed] = clsTpe match {
      case clv : ClassTypeVariable => clv.classDef.tree
      case clsDef : Trees.ClassDef[Typed] => clsDef
      case _ =>
        assert(false, s"can not retrieve inner class type of type $clsTpe. This is caused by a bug in the compiler.")
    }
    val params: List[tpd.Expr] = newExpr.params map typedExpr

    val expectTypes: List[Type] = innerClsTpe.params map { x => x.dealias.tpe }
    val realTypes: List[Type] = params map (_.tpe)

    @annotation.tailrec def recur(ts1: List[Type], ts2: List[Type]): Unit = (ts1, ts2) match {
      case (Nil, Nil) => ()
      case (Nil, _) | (_, Nil) =>
        throw TypeError(s"parameter list length mismatch: expect $expectTypes but found $realTypes").withPos(expr)
      case (t1 :: ts1, t2 :: ts2) =>
        recordEquality(t1, t2, expr.pos, None, None)
        recur(ts1, ts2)
    }
    recur(realTypes, expectTypes)

    newExpr.assignType(tpe = clsTpe, sym = innerClsTpe.sym, params = params)
  }

  /** Typing select expressions.
    */
  def typedSelectExpr(expr: untpd.SelectExpr): tpd.SelectExpr = {
    val select: Trees.SelectExpr[Untyped] = expr.tree
    val tpdExpr: tpd.Expr = typedExpr(select.expr)
    
    val tpe: Type = tryInstantiateType(tpdExpr.tpe) match {
      case None =>
        throw TypeError(s"can not infer the type of the expression being selected").withPos(expr)
      case Some(tp) => tp match {
        case tv: ClassTypeVariable =>
          tv.classDef.tree.members.find(m => m.tree.sym.name == select.member) match {
            case None =>
              throw TypeError(s"${tv.classDef.tree} does not have ${select.member}").withPos(expr)
            case Some(m) =>
              m.tpe.refTypeWhen(m.tree.mutable)
          }
        case cls: Trees.ClassDef[Typed] =>
          cls.members.find(m => m.tree.sym.name == select.member) match {
            case None =>
              throw TypeError(s"$cls does not have ${select.member}").withPos(expr)
            case Some(m) =>
              m.tpe.refTypeWhen(m.tree.mutable)
          }
        case tp =>
          throw TypeError(s"can not select member from type $tp").withPos(expr)
      }
    }
    
    select.assignType(tpe, tpdExpr)
  }

  /** Type binary operator expressions.
    */
  def typedBinOpExpr(expr: untpd.BinOpExpr): tpd.BinOpExpr = {
    val op = expr.tree.op
    val e1 = typedExpr(expr.tree.e1)
    val e2 = typedExpr(expr.tree.e2)
    val tpe = binOpSig get op match {
      case None => throw TypeError(s"unknown binary operator: $op").withPos(expr)
      case Some(sigs) => sigs.collectFirst {
        case sig if sig.infer(e1.tpe, e2.tpe, expr).isDefined => sig.infer(e1.tpe, e2.tpe, expr).get
      }
    } match {
      case None => throw TypeError(s"could not find override function for $op with operand type ${e1.tpe} and ${e2.tpe}").withPos(expr)
      case Some(tpe) => tpe
    }

    expr.tree.assignType(tpe, e1, e2)
  }

  /** Type unary operator expressions.
   */
  def typedUnaryOpExpr(expr: untpd.UnaryOpExpr): tpd.UnaryOpExpr = {
    val unaryExpr: Trees.UnaryOpExpr[Untyped] = expr.tree
    val op = unaryExpr.op
    val e = typedExpr(unaryExpr.e)
    val tpe = unaryOpSig get op match {
      case None => throw TypeError(s"unknown unary operator: $op").withPos(expr)
      case Some(sigs) => sigs collectFirst {
        case sig if sig.infer(e.tpe, expr).isDefined => sig.infer(e.tpe, expr).get
      }
    } match {
      case None => throw TypeError(s"could not find signature for $op with operand type ${e.tpe}").withPos(expr)
      case Some(tpe) => tpe
    }

    unaryExpr.assignType(tpe = tpe, e = e)
  }


  /** Signature for binary operators.
    */
  trait BinOpSig {
    /** Check and inference the result type of the binary operation.
      *
      * @param e1 Type for the left operand.
      * @param e2 Type for the right operand.
      * @return Inferred result type. `None` if operand type mismatch.
      */
    def infer(e1: Type, e2: Type, expr: untpd.BinOpExpr): Option[Type]
  }

  /** Concrete signature with fixed operand and result types.
    */
  case class ConcreteBinOpSig(e1: Type, e2: Type, res: Type) extends BinOpSig {
    override def infer(tp1: Type, tp2: Type, expr: untpd.BinOpExpr): Option[Type] = {
      val e1 = tryInstantiateType(tp1) getOrElse tp1
      val e2 = tryInstantiateType(tp2) getOrElse tp2
      if e1 == this.e1 && e2 == this.e2 then
        Some(res)
      else if e1 == this.e1 then {
        recordEquality(e2, this.e2, expr.pos, Some(expr.tree.e2.pos), None)
        Some(res)
      }
      else if e2 == this.e2 then {
        recordEquality(e1, this.e1, expr.pos, Some(expr.tree.e1.pos), None)
        Some(res)
      } else {
        None
      }
    }
  }

  /** Signature for unary operators.
    */
  trait UnaryOpSig {
    def infer(e: Type, expr: untpd.UnaryOpExpr): Option[Type]
  }

  case class ConcreteUnaryOpSig(e: Type, res: Type, ambiguous: Boolean = true) extends UnaryOpSig {
    override def infer(e: Type, expr: untpd.UnaryOpExpr): Option[Type] =
      tryInstantiateType(e) match {
        case None if !ambiguous =>
          recordEquality(e, this.e, expr.pos, Some(expr.tree.e.pos), None)
          Some(res)
        case Some(tpe) if tpe == this.e =>
          Some(res)
        case _ =>
          None
      }
  }

  /** Signature for built-in binary operators.
    */
  val binOpSig: Map[bop, List[BinOpSig]] = Map(
    bop.+ -> List(
      ConcreteBinOpSig(IntType, IntType, IntType),
      ConcreteBinOpSig(FloatType, FloatType, FloatType),
    ),
    bop.- -> List(
      ConcreteBinOpSig(IntType, IntType, IntType),
      ConcreteBinOpSig(FloatType, FloatType, FloatType),
    ),
    bop.% -> List(
      ConcreteBinOpSig(IntType, IntType, IntType),
    ),
    bop.* -> List(
      ConcreteBinOpSig(IntType, IntType, IntType),
      ConcreteBinOpSig(FloatType, FloatType, FloatType),
    ),
    bop./ -> List(
      ConcreteBinOpSig(IntType, IntType, IntType),
      ConcreteBinOpSig(FloatType, FloatType, FloatType),
    ),
    bop.^ -> List(
      ConcreteBinOpSig(IntType, IntType, IntType),
      ConcreteBinOpSig(FloatType, FloatType, FloatType),
    ),
    bop.> -> List(
      ConcreteBinOpSig(IntType, IntType, BooleanType),
      ConcreteBinOpSig(FloatType, FloatType, BooleanType),
    ),
    bop.< -> List(
      ConcreteBinOpSig(IntType, IntType, BooleanType),
      ConcreteBinOpSig(FloatType, FloatType, BooleanType),
    ),
    bop.>= -> List(
      ConcreteBinOpSig(IntType, IntType, BooleanType),
      ConcreteBinOpSig(FloatType, FloatType, BooleanType),
    ),
    bop.<= -> List(
      ConcreteBinOpSig(IntType, IntType, BooleanType),
      ConcreteBinOpSig(FloatType, FloatType, BooleanType),
    ),
    bop.&& -> List(
      ConcreteBinOpSig(BooleanType, BooleanType, BooleanType),
    ),
    bop.|| -> List(
      ConcreteBinOpSig(BooleanType, BooleanType, BooleanType),
    ),
    bop.== -> List(
      new BinOpSig {
        override def infer(e1: Type, e2: Type, expr: untpd.BinOpExpr): Option[Type] =
          if e1 == e2 then
            Some(BooleanType)
          else {
            recordEquality(e1, e2, expr.pos, Some(expr.tree.e1.pos), Some(expr.tree.e2.pos))
            Some(BooleanType)
          }
      }
    ),
    bop.!= -> List(
      new BinOpSig {
        override def infer(e1: Type, e2: Type, expr: untpd.BinOpExpr): Option[Type] =
          if e1 == e2 then
            Some(BooleanType)
          else {
            recordEquality(e1, e2, expr.pos, Some(expr.tree.e1.pos), Some(expr.tree.e2.pos))
            Some(BooleanType)
          }
      }
    ),
  )

  /** Signature for unary operators.
    */
  val unaryOpSig: Map[uop, List[UnaryOpSig]] = Map(
    uop.! -> List(
      ConcreteUnaryOpSig(BooleanType, BooleanType, ambiguous = false)
    ),
    uop.- -> List(
      ConcreteUnaryOpSig(IntType, IntType),
      ConcreteUnaryOpSig(FloatType, FloatType),
    ),
  )
}

object Typer {
  case class TypeError(msg: String) extends Exception with Positional {
    type PosSelf = TypeError
    override def toString: String = s"Type error:\n${showWithContext(2, msg)}"
  }

  /** Shows tpd.Expr.
    */
  def showTypedExpr(expr: tpd.Expr, indentLevel: Int = 0): String = {
    val tree: Trees.Expr[Typed] = expr.tree
    val tpe: Type = expr.tpe
    def showLambdaParam(param: Trees.LambdaParam): String =
      s"${param.sym.name} : ${param.tpe}"
    def showLocalDef(localDef: tpd.LocalDef): String = {
      val tree: Trees.LocalDef[Typed] = localDef.tree
      val tpe: Type = localDef.tpe
      tree match {
        case bind : Trees.LocalDef.Bind[Typed] =>
          val kw = if bind.mutable then "var" else "val"
          s"$kw ${bind.sym.name} : $tpe = ${showTypedExpr(bind.body, indentLevel)}"
        case eval : Trees.LocalDef.Eval[Typed] =>
          s"${showTypedExpr(eval.expr, indentLevel)}"
        case assign : Trees.LocalDef.Assign[Typed] =>
          s"${assign.ref} = ${showTypedExpr(assign.expr, indentLevel)}"
      }
    }
    tree match {
      case Trees.LambdaExpr(params, _, body) =>
        s"((${params map { sym => showLambdaParam(sym.dealias) } mkString ", "}) => ${showTypedExpr(body, indentLevel)}) : $tpe"
      case Trees.BlockExpr(defs, expr) =>
        s"{ ${defs map showLocalDef mkString "; "}; ${showTypedExpr(expr)} } : $tpe"
      case Trees.IdentifierExpr(sym) =>
        sym.toString + s" : $tpe"
      case Trees.LiteralIntExpr(value) =>
        value.toString + s" : $tpe"
      case Trees.LiteralFloatExpr(value) =>
        value.toString + s" : $tpe"
      case Trees.LiteralBooleanExpr(value) =>
        value.toString + s" : $tpe"
      case Trees.ApplyExpr(func, args) =>
        s"${showTypedExpr(func)} : $tpe"
      case Trees.SelectExpr(expr, member) =>
        s"(${showTypedExpr(expr)}.$member : $tpe)"
      case Trees.BinOpExpr(op, e1, e2) =>
        s"($op ${showTypedExpr(e1)} ${showTypedExpr(e2)}) : $tpe"
      case Trees.UnaryOpExpr(op, e) => ???
    }
  }
}

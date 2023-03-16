// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2023 ETH Zurich.

package viper.gobra.translator.encodings.defaults

import org.bitbucket.inkytonik.kiama.==>
import viper.gobra.ast.{internal => in}
import viper.gobra.translator.encodings.combinators.Encoding
import viper.gobra.translator.context.Context
import viper.gobra.translator.util.{ViperUtil => vu}
import viper.silver.ast.Method
import viper.silver.{ast => vpr}
import viper.gobra.translator.library.privates.{Private, PrivateImpl}

class DefaultMethodEncoding extends Encoding {

  import viper.gobra.translator.util.ViperWriter.{CodeLevel => cl, _}
  import MemberLevel._

  override def finalize (addMemberFn: vpr.Member => Unit): Unit = {
    privateProof.finalize(addMemberFn)
  } 

  private val privateProof: Private = new PrivateImpl

  override def method(ctx: Context): in.Member ==> MemberWriter[vpr.Method] = {
    case x: in.Method => methodDefault(x)(ctx)
    case x: in.Function => functionDefault(x)(ctx)
  }

  def methodDefault(x: in.Method)(ctx: Context): MemberWriter[vpr.Method] = {
    val (pos, info, errT) = x.vprMeta

    val vRecv = ctx.variable(x.receiver)
    val vRecvPres = ctx.varPrecondition(x.receiver).toVector

    val vArgs = x.args.map(ctx.variable)
    val vArgPres = x.args.flatMap(ctx.varPrecondition)

    val vResults = x.results.map(ctx.variable)
    val vResultPosts = x.results.flatMap(ctx.varPostcondition)
    val vResultInit = cl.seqns(x.results map ctx.initialization)

    //Depending if a private spec exists, we verify the public or private specification
    val (vPres, vPosts, vMeasures, vProof) = if (x.privateSpec.isEmpty) {
      ((vRecvPres ++ vArgPres) ++ x.pres.map(ctx.precondition),
        vResultPosts ++ x.posts.map(ctx.postcondition),
        x.terminationMeasures.map(e => pure(ctx.assertion(e))(ctx)),
        option(None))

    } else {
      val spec = x.privateSpec.getOrElse(null) //not empty

      ((vRecvPres ++ vArgPres) ++ spec.pres.map(ctx.precondition),
        vResultPosts ++ spec.posts.map(ctx.postcondition),
        spec.terminationMeasures.map(e => pure(ctx.assertion(e))(ctx)),
        //Generates a method to prove private entailment
        privateProof.privateProofMethod(x)(ctx))
      
    }

    for { 
      pres <- sequence(vPres)
      posts <- sequence(vPosts)
      measures <- sequence(vMeasures)

      body <- option(x.body.map{ b => block{
        for {
          init <- vResultInit
          core <- ctx.statement(b)
        } yield vu.seqn(Vector(init, core))(pos, info, errT)
      }})

      _ <- vProof

      method = vpr.Method(
        name = x.name.uniqueName,
        formalArgs = vRecv +: vArgs,
        formalReturns = vResults,
        pres = pres ++ measures,
        posts = posts,
        body = body
      )(pos, info, errT)

    } yield method
  }


  def functionDefault(x: in.Function)(ctx: Context): MemberWriter[Method] = {
    assert(x.info.origin.isDefined, s"$x has no defined source")

    val (pos, info, errT) = x.vprMeta

    val vArgs = x.args.map(ctx.variable)
    val vArgPres = x.args.flatMap(ctx.varPrecondition)

    val vResults = x.results.map(ctx.variable)
    val vResultPosts = x.results.flatMap(ctx.varPostcondition)
    val vResultInit = cl.seqns(x.results map ctx.initialization)

    //Depending if a private spec exists, we verify the public or private specification
    val (vPres, vPosts, vMeasures, vProof) = if (x.privateSpec.isEmpty) {
      (vArgPres ++ x.pres.map(ctx.precondition),
       vResultPosts ++ x.posts.map(ctx.postcondition),
       x.terminationMeasures.map(e => pure(ctx.assertion(e))(ctx)),
       option(None))

    } else {
      val spec = x.privateSpec.get //not empty

      (vArgPres ++ spec.pres.map(ctx.precondition),
       vResultPosts ++ spec.posts.map(ctx.postcondition),
       spec.terminationMeasures.map(e => pure(ctx.assertion(e))(ctx)),
       //Generates a method to prove private entailment
       privateProof.privateProofFunction(x)(ctx)) 
      
    }

    for {
      pres <- sequence(vPres)
      posts <- sequence(vPosts)
      measures <- sequence(vMeasures)

      body <- option(x.body.map{ b => block{
        for {
          init <- vResultInit
          core <- ctx.statement(b)
        } yield vu.seqn(Vector(init, core))(pos, info, errT)
      }})

      _ <- vProof

      method = vpr.Method(
        name = x.name.name,
        formalArgs = vArgs,
        formalReturns = vResults,
        pres = pres ++ measures,
        posts = posts,
        body = body
      )(pos, info, errT)

    } yield method
  }
}

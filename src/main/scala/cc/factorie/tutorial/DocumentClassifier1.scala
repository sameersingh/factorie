/* Copyright (C) 2008-2010 University of Massachusetts Amherst,
   Department of Computer Science.
   This file is part of "FACTORIE" (Factor graphs, Imperative, Extensible)
   http://factorie.cs.umass.edu, http://code.google.com/p/factorie/
   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at
    http://www.apache.org/licenses/LICENSE-2.0
   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License. */


package cc.factorie.tutorial
import java.io.File
import scala.collection.mutable.ArrayBuffer
import cc.factorie._
import cc.factorie.optimize._
import cc.factorie.app.classify

object DocumentClassifier1 {
  
  // Define variable classes
  object DocumentDomain extends CategoricalTensorDomain[String];
  class Document(file:File) extends BinaryFeatureVectorVariable[String] {
    def domain = DocumentDomain
    val label = new Label(file.getParentFile.getName, this)
    cc.factorie.app.strings.alphaSegmenter(file).foreach(token => this += token)
  }
  object LabelDomain extends CategoricalDomain[String];
  class Label(labelString:String, val document:Document) extends LabeledCategoricalVariable(labelString) {
    def domain = LabelDomain
  }
 
  def main(args:Array[String]): Unit = {
    implicit val random = new scala.util.Random(0)
    if (args.length < 2) 
      throw new Error("Usage: directory_class1 directory_class2 ...\nYou must specify at least two directories containing text files for classification.")

    // Read data and create Variables
    var docLabels = new ArrayBuffer[Label]()
    //var docLabels = new classify.LabelList[Label,Document](_.document)
    //var docLabels = new classify.LabelList((l:Label) => l.document)
    for (directory <- args) {
      val directoryFile = new File(directory)
      if (! directoryFile.exists) throw new IllegalArgumentException("Directory "+directory+" does not exist.")
      for (file <- new File(directory).listFiles; if (file.isFile)) {
        //println ("Directory "+directory+" File "+file+" documents.size "+documents.size)
        docLabels += new Document(file).label
      }
    }
    
    val (trainVariables, testVariables) = docLabels.shuffle.split(0.5)
    (trainVariables ++ testVariables).foreach(_.setRandomly)

    //println(model)
    //println(model.factors(trainVariables.head))
    val classifier = new OnlineLinearMultiClassTrainer().train(trainVariables, trainVariables.map(_.document))
    (trainVariables ++ testVariables).foreach(v => v.set(classifier.classification(v.document.value).bestLabelIndex)(null))
    val objective = HammingObjective
    println ("Train accuracy = "+ objective.accuracy(trainVariables))
    println ("Test  accuracy = "+ objective.accuracy(testVariables))

  }
}

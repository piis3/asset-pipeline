/*
* Copyright 2014 the original author or authors.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
*    http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

package asset.pipeline.handlebars

import asset.pipeline.AssetHelper
import asset.pipeline.AssetFile
import asset.pipeline.AssetCompiler
import asset.pipeline.AssetPipelineConfigHolder
import asset.pipeline.DirectiveProcessor
import org.mozilla.javascript.Context
import org.mozilla.javascript.Scriptable
import asset.pipeline.AbstractProcessor

class HandlebarsProcessor extends AbstractProcessor {

	Scriptable globalScope
	ClassLoader classLoader
	static def wrapTemplateCustom
	static org.mozilla.javascript.Script handlebarsCompilerScript
	static org.mozilla.javascript.Script handlebarsPrecompileScript
	private static final $LOCK = new Object[0]
	HandlebarsProcessor(AssetCompiler precompiler){
		super(precompiler)
		try {
			classLoader = getClass().getClassLoader()

			Context cx = Context.enter()
			cx.setOptimizationLevel(-1)
			globalScope = cx.initStandardObjects()
			loadHandlebars(cx)
			if (AssetPipelineConfigHolder.config?.handlebars?.wrapTemplate && !HandlebarsProcessor.wrapTemplateCustom) {
				HandlebarsProcessor.wrapTemplateCustom = new groovy.text.GStringTemplateEngine(this.class.classLoader).createTemplate(AssetPipelineConfigHolder.config?.handlebars?.wrapTemplate)
			}

		} catch (Exception e) {
			throw new Exception("Handlebars Engine initialization failed.", e)
		} finally {
			try {
				Context.exit()
			} catch (IllegalStateException e) {}
		}
	}

	public void loadHandlebars(Context cx) {
		String scanPath = AssetPipelineConfigHolder.config?.handlebars?.scanPath ?: 'handlebars.js'
		AssetFile handlebarsAssetFile = AssetHelper.fileForFullName(scanPath)
		if(!handlebarsCompilerScript) {
			synchronized($LOCK) {
				if(!handlebarsCompilerScript) {
					if(handlebarsAssetFile) {
						def directiveProcessor = new DirectiveProcessor('application/javascript')
						handlebarsCompilerScript = cx.compileString(directiveProcessor.compile(handlebarsAssetFile),handlebarsAssetFile.name,1,null)
						// cx.evaluateString globalScope, directiveProcessor.compile(handlebarsAssetFile), handlebarsAssetFile.name, 1, null
					} else {
						def handlebarsJsResource = classLoader.getResource('asset/pipeline/handlebars/handlebars.js')
						handlebarsCompilerScript = cx.compileString(handlebarsJsResource.getText('UTF-8'),handlebarsJsResource.file,1,null)
						// cx.evaluateString globalScope, handlebarsJsResource.getText('UTF-8'), handlebarsJsResource.file, 1, null
					}
				}
			}
		}

		handlebarsCompilerScript.exec(cx, globalScope)

		if(!handlebarsPrecompileScript) {
			synchronized($LOCK) {
				if(!handlebarsPrecompileScript) {
					handlebarsPrecompileScript = cx.compileString("Handlebars.precompile(handlebarsSrc)","HandlebarsCompileCommand",0,null)	
				}
			}
		}
		

	}

	String process(String input,AssetFile assetFile) {
		try {
			def cx = Context.enter()
			def compileScope = cx.newObject(globalScope)
			compileScope.setParentScope(globalScope)
			compileScope.put("handlebarsSrc", compileScope, input)
			def result = handlebarsPrecompileScript.exec(cx,compileScope)
			// def result = cx.evaluateString(compileScope, "Handlebars.precompile(handlebarsSrc)", "Handlebars compile command", 0, null)
			return wrapTemplate(templateNameForFile(assetFile), result)
		} catch (Exception e) {
			throw new Exception("""
			Handlebars Engine compilation of handlebars to javascript failed.
			$e
			""")
		} finally {
			Context.exit()
		}
	}

	def templateNameForFile(assetFile) {
		def templateRoot      = AssetPipelineConfigHolder.config?.handlebars?.templateRoot ?: 'templates'
		def templateSeperator = AssetPipelineConfigHolder.config?.handlebars?.templatePathSeperator ?: '/'

		def relativePath      = relativePath(assetFile.parentPath, templateRoot, templateSeperator)
		def templateName      = AssetHelper.nameWithoutExtension(assetFile.getName())
		if(relativePath) {
			templateName = [relativePath,templateName].join(templateSeperator)
		}
		return templateName
	}

	def relativePath(parentPath, templateRoot, templateSeperator) {
		if(!parentPath || parentPath == templateRoot) {
			return ""
		} else if(!parentPath.startsWith(templateRoot + '/')) {
			return parentPath.split("/").join(templateSeperator)
		}
		def path = parentPath.substring(templateRoot.size() + 1)
		return path.split("/").join(templateSeperator)
	}

	def wrapTemplate = { String templateName, String compiledTemplate ->
		
		if(HandlebarsProcessor.wrapTemplateCustom) {
			return HandlebarsProcessor.wrapTemplateCustom.make([compiledTemplate: compiledTemplate, templateName: templateName]).toString()
		} else {
			
			def partialComponents = templateName.split('/')
			if(partialComponents[-1].startsWith('_')) {
				partialComponents[-1] = partialComponents[-1].substring(1)
				def partialName = partialComponents.join('/')
				"""
		(function(){
			Handlebars.registerPartial('$partialName', $compiledTemplate);
				}());
				"""	
			} else {
				"""
		(function(){
			var template = Handlebars.template, templates = Handlebars.templates = Handlebars.templates || {};
				templates['$templateName'] = template($compiledTemplate);
				}());
				"""	
			}
			
		}
		
	}
}

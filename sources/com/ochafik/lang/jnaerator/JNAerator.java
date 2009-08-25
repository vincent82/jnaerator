/*
	Copyright (c) 2009 Olivier Chafik, All Rights Reserved
	
	This file is part of JNAerator (http://jnaerator.googlecode.com/).
	
	JNAerator is free software: you can redistribute it and/or modify
	it under the terms of the GNU Lesser General Public License as published by
	the Free Software Foundation, either version 3 of the License, or
	(at your option) any later version.
	
	JNAerator is distributed in the hope that it will be useful,
	but WITHOUT ANY WARRANTY; without even the implied warranty of
	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
	GNU Lesser General Public License for more details.
	
	You should have received a copy of the GNU Lesser General Public License
	along with JNAerator.  If not, see <http://www.gnu.org/licenses/>.
*/
package com.ochafik.lang.jnaerator;

import java.io.File;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.Diagnostic.Kind;

import org.anarres.cpp.LexerException;
import org.antlr.runtime.RecognitionException;
import org.junit.runner.JUnitCore;
import org.rococoa.cocoa.foundation.NSClass;

import com.ochafik.io.FileListUtils;
import com.ochafik.io.ReadText;
import com.ochafik.lang.compiler.CompilerUtils;
import com.ochafik.lang.compiler.MemoryFileManager;
import com.ochafik.lang.compiler.MemoryJavaFile;
import com.ochafik.lang.compiler.URLFileObject;
import com.ochafik.lang.jnaerator.JNAeratorCommandLineArgs.OptionDef;
import com.ochafik.lang.jnaerator.nativesupport.DllExport;
import com.ochafik.lang.jnaerator.parser.Arg;
import com.ochafik.lang.jnaerator.parser.Declaration;
import com.ochafik.lang.jnaerator.parser.Declarator;
import com.ochafik.lang.jnaerator.parser.Define;
import com.ochafik.lang.jnaerator.parser.Element;
import com.ochafik.lang.jnaerator.parser.Expression;
import com.ochafik.lang.jnaerator.parser.Function;
import com.ochafik.lang.jnaerator.parser.Identifier;
import com.ochafik.lang.jnaerator.parser.ModifiableElement;
import com.ochafik.lang.jnaerator.parser.Modifier;
import com.ochafik.lang.jnaerator.parser.ObjCppParser;
import com.ochafik.lang.jnaerator.parser.Scanner;
import com.ochafik.lang.jnaerator.parser.SourceFile;
import com.ochafik.lang.jnaerator.parser.Struct;
import com.ochafik.lang.jnaerator.parser.TypeRef;
import com.ochafik.lang.jnaerator.parser.VariablesDeclaration;
import com.ochafik.lang.jnaerator.parser.Expression.MemberRefStyle;
import com.ochafik.lang.jnaerator.parser.Identifier.QualificationSeparator;
import com.ochafik.lang.jnaerator.parser.Identifier.QualifiedIdentifier;
import com.ochafik.lang.jnaerator.parser.Identifier.SimpleIdentifier;
import com.ochafik.lang.jnaerator.parser.Struct.Type;
import com.ochafik.lang.jnaerator.runtime.LibraryExtractor;
import com.ochafik.lang.jnaerator.runtime.MangledFunctionMapper;
import com.ochafik.lang.jnaerator.runtime.Mangling;
import com.ochafik.lang.jnaerator.studio.JNAeratorStudio;
import com.ochafik.lang.jnaerator.studio.JNAeratorStudio.SyntaxException;
import com.ochafik.util.listenable.Adapter;
import com.ochafik.util.string.RegexUtils;
import com.ochafik.util.string.StringUtils;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import com.sun.jna.PointerType;
import static com.ochafik.lang.jnaerator.parser.ElementsHelper.*;
import static com.ochafik.lang.jnaerator.nativesupport.NativeExportUtils.*;
/*
//include com/ochafik/lang/jnaerator/parser/*.mm
//include com/ochafik/lang/jnaerator/parser/ObjCpp.g
 */


/**
 * java -Xmx2000m -jar ../bin/jnaerator.jar `for F in /System/Library/Frameworks/*.framework ; do echo $F| sed -E 's/^.*\/([^/]+)\.framework$/-framework \1/' ; done` -out apple-frameworks.jar
 */

public class JNAerator {

	public static interface Feedback {
		void setStatus(final String string);
		void setFinished(File toOpen);
		void setFinished(Throwable e);
		void sourcesParsed(SourceFiles sourceFiles);
		void wrappersGenerated(Result result);
	}
	private static Pattern argTokenPattern = Pattern.compile("(?m)\"[^\"]*\"|[^\\s]+");
	private static Pattern argVariablePattern = Pattern.compile("\\$\\(([^)]+)\\)");
	final JNAeratorConfig config;
	
	public JNAerator(JNAeratorConfig config) {
		this.config = config;
	}

	static final Pattern definePattern = Pattern.compile("#\\s*define\\s+(\\w+)\\s+(.*)");
	static final boolean fullFilePathInComments = true;
	
	private static final String DEFAULT_CONFIG_FILE = "config.jnaerator";
	protected static final Pattern fileRadixPattern = Pattern.compile("(?:[/\\\\]|^)(.*?)(?:Full\\.bridgesupport|\\.[^.]+)$");
	private static final Pattern classAndMethodNamePattern = Pattern.compile("(.+?)::([^:]+)");

	//"@C:\Prog\jnaerator\sources\com\ochafik\lang\jnaerator\nativesupport\dllexport.jnaerator"
	//"C:\Prog\CPP\CppLibTest\jnaerator\CppLibTest.jnaerator"
	public static void main(String[] argsArray) {
		if (argsArray.length == 0) {
			if (new File("/Users/ochafik").exists()) {
				argsArray = new String[] {
//						"-wikiHelp",
						//"/Developer/Platforms/iPhoneSimulator.platform/Developer/SDKs/iPhoneSimulator2.0.sdk/System/Library/Frameworks/Foundation.framework/Versions/C/Headers/NSURL.h",
						//"/Developer/Platforms/iPhoneSimulator.platform/Developer/SDKs/iPhoneSimulator2.0.sdk/System/Library/Frameworks/Foundation.framework/Versions/C/Headers",
//						"@/Users/ochafik/src/opencv-1.1.0/config.jnaerator",
//						"-library", "gc", 
//						"/Users/ochafik/src/gc6.8/include/",
//						"-I/Developer/SDKs/MacOSX10.5.sdk/usr/include",
//						"/Developer/SDKs/MacOSX10.4u.sdk/usr/include/sys/event.h",
//						"/Developer/SDKs/MacOSX10.4u.sdk/usr/include/machine/types.h",
//						"/Developer/SDKs/MacOSX10.4u.sdk/usr/include/sys/cdefs.h",
//						"/Developer/SDKs/MacOSX10.4u.sdk/usr/include/sys/_types.h",
//						"/Developer/SDKs/MacOSX10.4u.sdk/usr/include/stdint.h",
						
//						"-autoConf",
						//"-library", "c",
//						"-root", "org.rococoa",
						
//						"/Developer/SDKs/MacOSX10.4u.sdk/usr/include/sys/types.h", 
//						"/Developer/SDKs/MacOSX10.4u.sdk/usr/include/architecture/i386/math.h",
//						"/System/Library/Frameworks/Foundation.framework/Headers/NSObjCRuntime.h",
//						"/System/Library/Frameworks/ApplicationServices.framework/Versions/Current/Frameworks/CoreGraphics.framework/Headers/CGBase.h",
//						"/System/Library/Frameworks/ApplicationServices.framework/Versions/Current/Frameworks/CoreGraphics.framework/Headers/CGShading.h",
//						"/Developer/SDKs/MacOSX10.4u.sdk/usr/include/AvailabilityMacros.h",
//						"/Users/ochafik/Prog/Java/testxp/test.h",
//						"/Users/ochafik/Prog/Java/test/Test2.h",
//						"-library", "objc",
//						"/Developer/SDKs/MacOSX10.4u.sdk/usr/include/objc/objc.h",
//						"-framework", "Foundation",
//						"-framework", "AppKit",
//						"-framework", "CoreFoundation",
//						"-framework", "IOKit",
//						"/System/Library/Frameworks/Foundation.framework/Headers/NSArray.h",
//						"/System/Library/Frameworks/Foundation.framework/Headers/NSString.h",
//						"/System/Library/Frameworks/Foundation.framework/Headers/NSObject.h",
//						"-framework", "CoreGraphics", 
//						"-framework", "CarbonCore", 
						//"-f", "QTKit", 
//						"-o", "/Users/ochafik/Prog/Java/test/objc",
//						"-o", "/Users/ochafik/Prog/Java/testxp",
//						"/Users/ochafik/Prog/Java/test/Test.h",
//						"/Users/ochafik/Prog/Java/test/JNATest.h",
						//"-o", "/Users/ochafik/Prog/Java",
//						"@/Users/ochafik/src/opencv-1.1.0/config.jnaerator",
//						"-library", "CocoaTest", "-o", "/Users/ochafik/Prog/Java/test/cppxcode",
//						"/Users/ochafik/Prog/Java/versionedSources/jnaerator/trunk/examples/XCode/CocoaTest/TestClass.h",
						
//						"@/Users/ochafik/src/qhull-2003.1/qhull.jnaerator",
//						"@",
//						"/Users/ochafik/Prog/Java/versionedSources/jnaerator/trunk/examples/Rococoa/cocoa.jnaerator",
//						"-limitComments",
//						"@/Users/ochafik/src/opencv-1.1.0/config.jnaerator",
//						"-o", "/Users/ochafik/src/opencv-1.1.0",
//						"/Users/ochafik/Prog/Java/test/cocoa/cocoa.h",
//						"/tmp/BridgeSupportTiger/Release/Library/BridgeSupport/CoreGraphics.bridgesupport"
//						"/tmp/BridgeSupportTiger/Release/Library/BridgeSupport/CoreFoundation.bridgesupport"
//						"-framework", "CoreGraphics",
//						"-o", "/Users/ochafik/Prog/Java/test/foundation2",
//						"-noRuntime",
						"-root", "org.rococoa.cocoa",
						"/System/Library/Frameworks/Foundation.framework/Resources/BridgeSupport/FoundationFull.bridgesupport",
						"-o", "/Users/ochafik/Prog/Java/test/bridgesupport",
//						"-noComp",	
//						"-gui",
//						"-jar", "/Users/ochafik/Prog/Java/test/foundation2/test.jar",
//						"@/Users/ochafik/Prog/Java/versionedSources/nativelibs4java/trunk/libraries/MacOSXFrameworks/config.jnaerator"
//						"-library", "opencl",
//						"/Users/ochafik/src/opencl/cl.h",
//						"-o", "/Users/ochafik/src/opencl",
						"-v"
				};
			} else if (new File(DEFAULT_CONFIG_FILE).exists()){
				argsArray = new String[] { "@", DEFAULT_CONFIG_FILE };
			} else {
				JNAeratorCommandLineArgs.displayHelp(false);
				return;
			}
		}
		
		try {
			List<String> args = new ArrayList<String>(Arrays.asList(argsArray));
			
			final JNAeratorConfig config = new JNAeratorConfig();
			config.preprocessorConfig.frameworksPath.addAll(JNAeratorConfigUtils.DEFAULT_FRAMEWORKS_PATH);
			new JNAeratorCommandLineArgs.ArgsParser() {

				Feedback feedback = null;
				
				List<String> frameworks = new ArrayList<String>();
				boolean simpleGUI = false;
				String arch = LibraryExtractor.getCurrentOSAndArchString();
				String currentLibrary = null;
				
				@Override
				List<String> parsed(ParsedArg a) throws Exception {
					switch (a.def) {					
					
					case AddIncludePath:
						config.preprocessorConfig.includes.add(a.getFileParam(0).toString());
						break;
					case AddFrameworksPath:
						config.preprocessorConfig.frameworksPath.add(a.getFileParam(0).toString());
						break;
					case NoPreprocessing:
						config.preprocessorConfig.preprocess = false;
						break;
					case MaxConstructedFields:
						config.maxConstructedFields = a.getIntParam(0);
						break;
					case NoPrimitiveArrays:
						config.noPrimitiveArrays = true;
						break;
					case NoCompile:
						config.compile = false;
						break;
					case NoBufferReturns:
						config.returnNIOBuffersForPrimitivePointers = false;
						break;
					case NoStringReturns:
						config.stringifyConstCStringReturnValues = false;
						break;
					case CPlusPlusGen:
						config.genCPlusPlus = true;
						break;
					case CurrentLibrary:
						currentLibrary = a.getStringParam(0);
						break;
					case CurrentPackage:
						config.packageName = a.getStringParam(0);
						break;
					case NoLibBundle:
						config.bundleLibraries = false;
						break;
					case DefaultLibrary:
						config.defaultLibrary = a.getStringParam(0);
						break;
					case DefineMacro:
						config.preprocessorConfig.macros.put(a.getStringParam(0), a.getStringParam(1));
						break;
					case Direct:
						config.useJNADirectCalls = true;
						break;
					case EntryName:
						config.entryName = a.getStringParam(0);
						break;
					case ExtractSymbols:
						config.extractLibSymbols = true;
						break;
					case File:
						return parsedFile(a);
					case FrameworksPath:
						config.preprocessorConfig.frameworksPath.clear();
						config.preprocessorConfig.frameworksPath.addAll(Arrays.asList(a.getStringParam(0).split(":")));
						break;
					case GUI:
						simpleGUI = true;
						break;
					case Help:
					case WikiDoc:
						JNAeratorCommandLineArgs.displayHelp(a.def == OptionDef.WikiDoc);
						System.exit(0);
						break;
					case WCharAsShort:
						config.wcharAsShort = true;
						break;
					case JarOut:
						config.outputJar = a.getFileParam(0);
						break;
					case NoMangling:
						config.noMangling = true;
						break;
					case NoComments:
						config.noComments = true;
						break;
					case LimitComments:
						config.limitComments = true;
						break;
					case MacrosOut:
						config.macrosOutFile = a.getFileParam(0);
						break;
					case NoAuto:
						config.autoConf = false;
						break;
					case NoCPP:
						config.noCPlusPlus = true;
						break;
					case NoRuntime:
						config.bundleRuntime = false;
						break;
					case OutputDir:
						config.outputDir = a.getFileParam(0);
						break;
					case PreferJavac:
						config.preferJavac = true;
						break;
					case BridgeSupportOutFile:
						config.bridgesupportOutFile = a.getFileParam(0);
						break;
						
					case PreprocessingOut:
						config.preprocessingOutFile = a.getFileParam(0);
						break;
					case ExtractionOut:
						config.extractedSymbolsOut = a.getFileParam(0);
						break;
						
					case Project:
						JNAeratorConfigUtils.readProjectConfig(a.getFileParam(0), a.getStringParam(1), config);
						break;
					case RootPackage:
						config.rootPackageName = a.getStringParam(0);
						break;
					case StructsInLibrary:
						config.putTopStructsInSeparateFiles = false;
						break;
					case Studio:
						try {
							JNAeratorStudio.main(new String[0]);
							return null;
						} catch (Exception ex) {
							ex.printStackTrace();
							System.exit(1);
						}
						break;
					case Test:
						try {
							JUnitCore.main(JNAeratorTests.class.getName());
							System.exit(0);
						} catch (Exception ex) {
							ex.printStackTrace();
							System.exit(1);
						}
						break;
					case Verbose:
						config.verbose = true;
						break;
					case Framework:
						frameworks.add(a.getStringParam(0));
						break;
					case IncludeArgs:
						return parsedArgsInclude(a);
					case Arch:
						arch = a.getStringParam(0);
						break;
					
					}
					return Collections.emptyList();
				}

				private List<String> parsedFile(ParsedArg a) throws Exception {
					File file = a.getFileParam(0);
					if (file != null) {
						String fn = file.getName();
						if (file.isDirectory() && fn.matches(".*\\.framework"))
							frameworks.add(file.toString());
						else if (file.isFile() && fn.matches(".*\\.jnaerator"))
							return parsedArgsInclude(a);
						else if (fn.matches(".*\\.bridgesupport"))
							config.bridgeSupportFiles.add(file);
						else if (file.isFile() && isLibraryFile(file)) {
							if (config.verbose)
								System.out.println("Adding file '" + file + "' for arch '" + arch +"'.");
							config.addLibraryFile(file, arch);
						} else {
							String lib = currentLibrary;
							if (file.isDirectory() && fn.endsWith(".xcode") ||
								file.isFile() && fn.toLowerCase().endsWith(".sln")) 
							{
								JNAeratorConfigUtils.readProjectConfig(file, null, config);
							} else {
								if (lib == null) {
									String name = fn;
									int i = name.indexOf('.');
									if (i >= 0)
										name = name.substring(0, i).trim();
									if (name.length() > 0)
										lib = name;
									System.out.println("Warning: no -library option for file '" + fn + "', using \"" + lib + "\".");
								}
								config.addSourceFile(file, lib);//config.defaultLibrary);
							}
						}
					}
					return Collections.emptyList();
				}

				private List<String> parsedArgsInclude(ParsedArg a) throws IOException {
					final File argsFile = a.getFileParam(0);
					
					String argsFileContent = ReadText.readText(argsFile);
					Adapter<String[], String> argVariableReplacer = new Adapter<String[], String>() {
						@Override
						public String adapt(String[] value) {
							String n = value[1];
							String v = System.getProperty(n);
							if (v == null)
								v = System.getenv(n);
							if (v == null && n.equals("DIR"))
								v = argsFile.getAbsoluteFile().getParent();
							return v;
						}
					};
					
					// Strip comments out
					argsFileContent = argsFileContent.replaceAll("(?m)//[^\n]*(\n|$)", "\n");
					argsFileContent = argsFileContent.replaceAll("(?m)/\\*([^*]|\\*[^/])*\\*/", "");
					
					// Replace variables
					argsFileContent = RegexUtils.regexReplace(argVariablePattern, argsFileContent, argVariableReplacer);
					
					List<String> ret = new ArrayList<String>();
					List<String[]> tokens = RegexUtils.find(argsFileContent, argTokenPattern);
					for (String[] tokenMatch : tokens) {
						String token = tokenMatch[0];
						token = token.trim();
						if (token.startsWith("\"") && token.endsWith("\""))
							token = token.substring(1, token.length() - 1);
						
						if (token.length() == 0 || token.matches("^(//|#).*"))
							continue;
						
						boolean allowMissing = token.endsWith("?");
						if (token.contains("*")) {
							Collection<String> rs = FileListUtils.resolveShellLikeFileList(allowMissing ? token.substring(0, token.length() - 1) : token);
							for (String r : rs)
								ret.add(allowMissing ? r + "?" : r);
							if (!rs.isEmpty())
								continue;
						}
						ret.add(token);
					}
					return ret;
				}

				@Override
				void finished() throws IOException {
					for (String framework : frameworks)
						JNAeratorConfigUtils.addFramework(config, framework);
					
					config.addRootDir(new File("."));
					for (String i : config.preprocessorConfig.includes) {
						try {
							config.addRootDir(new File(i));
						} catch (Exception ex) {}
					}	
					
					if (config.sourceFiles.isEmpty() && config.bridgeSupportFiles.isEmpty() && !config.libraryFiles.isEmpty())
						config.extractLibSymbols = true;
					
					Collection<File> inputFiles = config.getInputFiles();
					File firstFile = inputFiles.isEmpty() ? null : inputFiles.iterator().next().getAbsoluteFile();
					String firstFileName = firstFile == null ? null : firstFile.getName();
					String entry = config.entryName == null ? RegexUtils.findFirst(firstFileName, fileRadixPattern, 1) : config.entryName; 
						
					if (config.outputDir == null)
						config.outputDir = firstFile == null ? new File(".") : firstFile.getAbsoluteFile().getParentFile();
					
					if (config.outputJar == null && config.compile)
						config.outputJar = new File(config.outputDir, (entry == null ? "out" : entry) + ".jar");
					
					if (config.verbose) {
						if (config.macrosOutFile == null)
							config.macrosOutFile = new File("_jnaerator.macros.cpp");
						if (config.preprocessingOutFile == null)
							config.preprocessingOutFile = new File("_jnaerator.preprocessed.c");
						if (config.extractedSymbolsOut == null)
							config.extractedSymbolsOut = new File("_jnaerator.extractedSymbols.h");
						if (config.bridgesupportOutFile == null)
							config.bridgesupportOutFile = new File("_jnaerator.bridgesupport.h");
					}
					
					config.cacheDir = getDir("cache");
					
					if (simpleGUI) {
						SimpleGUI gui = new SimpleGUI(config);
						feedback = gui;
						gui.show();
					} else {
						feedback = new Feedback() {
							
							@Override
							public void setStatus(String string) {
								if (config.verbose)
									System.out.println(string);
							}
							
							@Override
							public void setFinished(Throwable e) {
								System.out.println("JNAeration failed !");
								e.printStackTrace();
								System.exit(1);
							}
							
							@Override
							public void setFinished(File toOpen) {
								System.out.println("JNAeration completed !");
								System.out.println(toOpen.getAbsolutePath());
								System.exit(0);
							}

							@Override
							public void sourcesParsed(SourceFiles sourceFiles) {
								
							}

							@Override
							public void wrappersGenerated(
									com.ochafik.lang.jnaerator.Result result) {
								// TODO Auto-generated method stub
								
							}
						}; 
					}
					
					new JNAerator(config).jnaerate(feedback);
					if (!simpleGUI)
						System.exit(0);
				}
				
			}.parse(args);
			
		} catch (Exception e) {
			e.printStackTrace();
			System.exit(1);
		}
	}
	public PrintWriter getClassSourceWriter(ClassOutputter outputter, String className) throws IOException {
		return outputter.getClassSourceWriter(className);
	}
	private static boolean isLibraryFile(File file) {
		String arg = file.getName().toLowerCase();
		return 
			arg.endsWith(".dll") || 
			arg.endsWith(".pdb") || 
			arg.endsWith(".dylib") || 
			arg.endsWith(".so") || 
			arg.endsWith(".jnilib");
	}
	public void jnaerate(final Feedback feedback) {
		try {
			if (config.autoConf) {
				feedback.setStatus("Auto-configuring parser...");
				JNAeratorConfigUtils.autoConfigure(config);
			}
			
			if (config.verbose)
				JNAeratorConfigUtils.logger.log(Level.INFO, "Include path : \n\t" + StringUtils.implode(config.preprocessorConfig.includes, "\n\t"));
			
			DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<JavaFileObject>();
			JavaCompiler c = CompilerUtils.getJavaCompiler(config.preferJavac);
			final MemoryFileManager mfm = new MemoryFileManager(c.getStandardFileManager(diagnostics, null, null));
			
			final ClassOutputter[] classOutputter = new ClassOutputter[1];
			if (config.compile) {
				classOutputter[0] = new ClassOutputter() {
					@Override
					public PrintWriter getClassSourceWriter(String className) throws FileNotFoundException {
						String path = "file:///" + className.replace('.', '/') + ".java";
						MemoryJavaFile c = new MemoryJavaFile(path, (String)null, JavaFileObject.Kind.SOURCE);
						mfm.inputs.put(c.getPath().toString(), c);
						return new PrintWriter(c.openWriter());
					}
				};
			} else {
				classOutputter[0] = new ClassOutputter() {
					public PrintWriter getClassSourceWriter(String className) throws FileNotFoundException {
						File file = new File(JNAerator.this.config.outputDir, className.replace('.', File.separatorChar) + ".java");
						File parent = file.getParentFile();
						if (!parent.exists())
							parent.mkdirs();
	
						feedback.setStatus("Generating " + file.getName());
	
						return new PrintWriter(file) {
							@Override
							public void print(String s) {
								super.print(s.replace("\r", "").replace("\n", StringUtils.LINE_SEPARATOR));
							}
						};
					}
				};
			}
			
			Result result = createResult(new ClassOutputter() {
				
				@Override
				public PrintWriter getClassSourceWriter(String className) throws IOException {
					return JNAerator.this.getClassSourceWriter(classOutputter[0], className);
				}
			});
			
			SourceFiles sourceFiles = parseSources(feedback, result.typeConverter);
			if (config.extractLibSymbols)
				parseLibSymbols(sourceFiles, feedback, result);
			
			feedback.sourcesParsed(sourceFiles);
			
			jnaerationCore(sourceFiles, result, feedback);
			feedback.wrappersGenerated(result);
			
			if (config.compile) {
				for (Map.Entry<String, String> cnAndSrc : config.extraJavaSourceFilesContents.entrySet()) {
					mfm.addSourceInput(cnAndSrc.getKey(), cnAndSrc.getValue());
				}
				feedback.setStatus("Compiling JNAerated files...");
				CompilerUtils.compile(c, mfm, diagnostics, "1.5", config.cacheDir, NativeLibrary.class, JNAerator.class, NSClass.class, Mangling.class);
				if (!diagnostics.getDiagnostics().isEmpty()) {
					StringBuilder sb = new StringBuilder();
					
					for (final Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
						if (diagnostic == null)
							continue;
						if (diagnostic.getKind() == Kind.ERROR) {
							sb.append("Error in " + diagnostic.getSource().toUri() + " at line " + diagnostic.getLineNumber() + ", col " + diagnostic.getColumnNumber() + " :\n\t" + diagnostic.getMessage(Locale.getDefault()) + "\n");//.toUri());
							sb.append(RegexUtils.regexReplace(Pattern.compile("\n"), "\n" +  diagnostic.getSource().getCharContent(true), new Adapter<String[], String>() {
								int line = 0;

								@Override
								public String adapt(String[] value) {
									line++;
									return "\n" + line + ":" + (diagnostic.getLineNumber() == line ? ">>>" : "") +"\t\t";
								}
							}) + "\n");
						}
//							System.out.println("Error on line " + diagnostic.getLineNumber() + ":" + diagnostic.getColumnNumber() + " in " + (diagnostic.getSource() == null ? "<unknown source>" : diagnostic.getSource().getName()) + ": " + diagnostic.getMessage(Locale.getDefault()));
					}
//					for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
//						if (diagnostic.getKind() == Kind.ERROR)
//							sb.append("Error on line " + diagnostic.getLineNumber() + ":" + diagnostic.getColumnNumber() + " in " + diagnostic.getSource().getName() + "\n\t" + diagnostic.getMessage(Locale.getDefault()) + "\n");//.toUri());
//					}
					if (sb.length() > 0) {
						//System.out.println(sb);
						throw new SyntaxException(sb.toString());
					}
				}
				if (config.outputJar != null && result.config.bundleRuntime) {
					feedback.setStatus("Copying runtime classes...");
					addRuntimeClasses(result, mfm);
				}
			}
			if (config.outputJar != null) {
				
				feedback.setStatus("Generating " + config.outputJar.getName());
				mfm.writeJar(config.outputJar, config.bundleSources, getAdditionalFiles());
			}
//			if (true)
//				throw new RuntimeException("no luck !");
			feedback.setFinished(config.outputJar != null ? config.outputJar : config.outputDir);
		} catch (Throwable th) {
			feedback.setFinished(th);
		}
	}
	public void parseLibSymbols(SourceFiles sourceFiles, Feedback feedback, Result result) throws FileNotFoundException {
		PrintWriter fileOut = null;
		if (config.extractedSymbolsOut != null) {
			if (config.verbose)
				System.out.println("Writing symbols extracted from libraries to '" + config.extractedSymbolsOut + "'");
			fileOut = new PrintWriter(config.extractedSymbolsOut);
		}
		
		for (File libFile : config.libraryFiles) {
			if (libFile.getName().toLowerCase().endsWith(".dll")) {
				try {
					feedback.setStatus("Extracting symbols from " + libFile.getName() + "...");
					
					SourceFile sf = new SourceFile();
					sf.setElementFile(libFile.toString());
					List<ParsedExport> dllExports = DllExport.parseDllExports(libFile);
					Map<String, Struct> cppClasses = new HashMap<String, Struct>();
					Pattern pubPat = Pattern.compile("(public|private|protected):(.*)");
					for (ParsedExport dllExport : dllExports) {
						//dllExport.mangling
						String dem = dllExport.demangled;
						Matcher m = pubPat.matcher(dem);
						String pub = null;
						if (m.matches()) {
							dem = m.group(2);
							pub = m.group(1);
						}
						String text = "// @mangling " + dllExport.mangling + "\n" + 
							dem + ";";
						ObjCppParser parser = JNAeratorParser.newObjCppParser(result.typeConverter, text, config.verbose);
						parser.setupSymbolsStack();
						List<Declaration> decls = parser.declarationEOF();
						if (decls == null)
							continue;
						
						for (Declaration decl : decls) {
							if (decl instanceof VariablesDeclaration && decl.getValueType() != null)
								decl.getValueType().addModifiers(Modifier.Extern);
							decl.addModifiers(Modifier.parseModifier(pub));
							if (decl instanceof Function) {
								Function f = (Function)decl;
								List<SimpleIdentifier> si = new ArrayList<SimpleIdentifier>(f.getName().resolveSimpleIdentifiers());
								Identifier ci;
								if (si.size() == 1) {
									String name = si.get(0) == null ? null : si.get(0).toString();
									String[] cm = name == null ? null : RegexUtils.match(name, classAndMethodNamePattern);
									if (cm == null) {
										sf.addDeclaration(decl);
										continue;
									}
									ci = ident(cm[0]);
									f.setName(ident(cm[1]));
								} else {
									si.remove(si.size() - 1);
									ci = new QualifiedIdentifier(QualificationSeparator.Colons, si);
								}
								if (dem.contains("__thiscall"))
									f.addModifiers(Modifier.__thiscall);
								if (dem.contains("__fastcall"))
									f.addModifiers(Modifier.__fastcall);
								
								Struct s = cppClasses.get(ci.toString());
								if (s == null) {
									s = new Struct();
									cppClasses.put(ci.toString(), s);
									s.setType(Struct.Type.CPPClass);
									s.setTag(ci.clone());
									sf.addDeclaration(decl(s));
								}
								Identifier n = f.getName().resolveLastSimpleIdentifier();
//										String ns = n.toString();
//										if (ns.startsWith("_"))
//											n = ident(ns.substring(1));
								f.setName(n);
								s.addDeclaration(f);
							} else
								sf.addDeclaration(decl);
						}
					}
					if (!sf.getDeclarations().isEmpty()) {
						sourceFiles.add(sf);
						if (fileOut != null)
							fileOut.println(sf);
					}
					
				} catch (Throwable ex) {
					ex.printStackTrace();
				}
			}
		}
		if (fileOut != null)
			fileOut.close();
	}
	private Map<String, File> getAdditionalFiles() {

		Map<String, File> additionalFiles = new HashMap<String,File>();

		if (config.bundleLibraries) {
			for (Map.Entry<String, List<File>> e : config.libraryFilesByArch.entrySet()) {
				String arch = e.getKey();
				for (File libraryFile : e.getValue())
					additionalFiles.put(
						"libraries/" + (arch == null || arch.length() == 0 ? "" : arch + "/") + libraryFile.getName(), 
						libraryFile
					);
			}
			for (String library : new HashSet<String>(config.libraryByFile.values())) {
				String libraryFileName = System.mapLibraryName(library);
				File libraryFile = new File(libraryFileName); 
				//TODO lookup in library path
				if (!libraryFile.exists() && libraryFileName.endsWith(".jnilib"))
					libraryFile = new File(libraryFileName = libraryFileName.substring(0, libraryFileName.length() - ".jnilib".length()) + ".dylib");
					
				String key = "libraries/" + LibraryExtractor.getCurrentOSAndArchString() + "/" + libraryFile.getName();
				if (additionalFiles.containsKey(key))
					continue;
				
				if (libraryFile.exists()) {
					System.out.println("Bundling " + libraryFile);
					additionalFiles.put(key, libraryFile);
				} else {
					System.out.println("File " + libraryFileName + " not found");
				}
			}
		}
		return additionalFiles;
	}
	protected void addRuntimeClasses(Result result, MemoryFileManager mfm) throws IOException {
		
		ClassLoader classLoader = JNAerator.class.getClassLoader();
		String listingFile = "jnaerator-runtime.jar.files";
		List<String> files = ReadText.readLines(classLoader.getResourceAsStream(listingFile ));
		
		try {
			if (files == null)
				files = ReadText.readLines("/Users/ochafik/Prog/Java/bin/jnaerator-runtime.jar.files");
		} catch (Exception ex) {}
//		if (files == null)
//			throw new FileNotFoundException(listingFile);
		
		if (files == null) {
			throw new FileNotFoundException("Warning: Could not find JNAerator listing file '" + listingFile + "' : JNAerated files will need JNAerator in the path to execute.");
//			ex.printStackTrace();
//			ex.printStackTrace(new PrintStream(new FileOutputStream(FileDescriptor.err)));
//			return;
		}
		
		boolean needsObjCRuntime = result.hasObjectiveC();
		for (String file : files) {
			if (!needsObjCRuntime) {
				if (!file.startsWith("com/ochafik") &&
						!file.startsWith("com/sun/jna"))
					continue;
			}
			
			URL url = classLoader.getResource(file);
			if (url == null) {
				if (file.matches("com/sun/jna/[^/]+/(lib\\w+\\.(jnilib|so)|\\w+\\.dll)")) {
					System.out.println("JNA library missing : " + file);
					continue;
				}
				throw new FileNotFoundException(file);
			}
			
			file = "file:///" + file;
			if (!mfm.outputs.containsKey(file)) {
				mfm.outputs.put(file, new URLFileObject(url));
			}
		}
		/*
		for (URL sourceJar : new URL[] {
				ClassUtils.getClassPath(NativeLibrary.class),
				ClassUtils.getClassPath(Rococoa.class)
		})
			for (URL resURL : URLUtils.listFiles(sourceJar, null)) {
				String s = resURL.getFile();
				if (s.startsWith("META-INF"))
					continue;
				
				if (!mfm.outputs.containsKey(s)) {
					mfm.outputs.put(s, new URLFileObject(resURL));
				}
			}
		*/
	}
	public static File getDir(String name) {
		File dir = new File(getDir(), name);
		dir.mkdirs();
		return dir;
	}
	public static File getDir() {
		File dir = new File(System.getProperty("user.home"));
		dir = new File(dir, ".jnaerator");
		dir = new File(dir, "temp");
		dir.mkdirs();
		return dir;
	}
	static boolean isHexDigit(char c) {
		return 
			c >= 'A' && c <= 'F' ||
			c >= 'a' && c <= 'f' ||
			Character.isDigit(c);
	}
	static void escapeUnicode(String s, StringBuilder bout) {
		bout.setLength(0);
		char[] chars = s.toCharArray();
		for (int iChar = 0, nChars = chars.length; iChar < nChars; iChar++) {
			char c = chars[iChar];
			int v = (int)c;
			if (v > 127) {
				bout.append("\\u");
				String h = Integer.toHexString(v);
				for (int i = 4 - h.length(); i-- != 0;)
					bout.append('0');
				bout.append(h);
			} else {
				// handle \\uXXXX -> \\uuXXXX transformation :
//				if (c == '\\' && 
//						iChar < nChars - 5 && 
//						chars[iChar + 1] == 'u' &&
//						isHexDigit(chars[iChar + 2]) &&
//						isHexDigit(chars[iChar + 3]) &&
//						isHexDigit(chars[iChar + 4]) &&
//						isHexDigit(chars[iChar + 5])
//				) {
//					bout.append("\\u");
//				}
				bout.append(c);
			}
		}
	}
			
	public SourceFiles parseSources(Feedback feedback, TypeConversion typeConverter) throws IOException, LexerException {
		feedback.setStatus("Parsing native headers...");
		return JNAeratorParser.parse(config, typeConverter);
	}
	public void addFile(File file, List<File> out) throws IOException {
		if (file.isFile()) {
			out.add(file);
		} else {
			File[] fs = file.listFiles();
			if (fs != null) {
				for (File f : fs) {
					addFile(f, out);
				}
			}
		}
	}
	
	private static void generateLibraryFiles(SourceFiles sourceFiles, Result result) throws IOException {
		
		Struct librariesHub = null;
		PrintWriter hubOut = null;
		if (result.config.entryName != null) {
			librariesHub = new Struct();
			librariesHub.addToCommentBefore("JNA Wrappers instances");
			librariesHub.setType(Type.JavaClass);
			librariesHub.addModifiers(Modifier.Public, Modifier.Abstract);
			Identifier hubName = result.getHubFullClassName();
			librariesHub.setTag(hubName.resolveLastSimpleIdentifier());
			hubOut = result.classOutputter.getClassSourceWriter(hubName.toString());
			hubOut.println("package " + hubName.resolveAllButLastIdentifier() + ";");
			for (Identifier pn : result.javaPackages)
				if (!pn.equals(""))
					hubOut.println("import " + pn + ".*;");
		}
		for (String library : result.libraries) {
			if (library == null)
				continue; // to handle code defined in macro-expanded expressions
//				library = "";
			
			Identifier javaPackage = result.javaPackageByLibrary.get(library);
			Identifier simpleLibraryClassName = result.getLibraryClassSimpleName(library);
			
			Identifier fullLibraryClassName = result.getLibraryClassFullName(library);//ident(javaPackage, libraryClassName);
			//if (!result.objCClasses.isEmpty())
			//	out.println("import org.rococoa.ID;");
			
			
			Struct interf = new Struct();
			interf.addToCommentBefore("JNA Wrapper for library <b>" + library + "</b>",
					result.declarationsConverter.getFileCommentContent(result.config.libraryProjectSources.get(library), null)
			);
			if (hubOut != null)
				interf.addToCommentBefore("@see " + result.config.entryName + "." + library);
			
			interf.addModifiers(Modifier.Public);
			interf.setTag(simpleLibraryClassName);
			Identifier libSuperInter = ident(Library.class);
			if (result.config.useJNADirectCalls) {
				interf.addProtocols(libSuperInter);
				interf.setType(Type.JavaClass);
			} else {
				interf.setParents(libSuperInter);
				interf.setType(Type.JavaInterface);
			}
			
			Expression libNameExpr = opaqueExpr(result.getLibraryFileExpression(library));
			TypeRef libTypeRef = typeRef(fullLibraryClassName);
			Expression libClassLiteral = classLiteral(libTypeRef);
			
			Expression libraryPathGetterExpr = methodCall(
				expr(typeRef(LibraryExtractor.class)),
				MemberRefStyle.Dot,
				"getLibraryPath",
				libNameExpr,
				expr(true),
				libClassLiteral
			);
			
			String libNameStringFieldName = "JNA_LIBRARY_NAME", nativeLibFieldName = "JNA_NATIVE_LIB";
			interf.addDeclaration(new VariablesDeclaration(typeRef(String.class), new Declarator.DirectDeclarator(
				libNameStringFieldName,
				libraryPathGetterExpr
			)).addModifiers(Modifier.Public, Modifier.Static, Modifier.Final));
			
			Expression libraryNameFieldExpr = memberRef(expr(libTypeRef.clone()), MemberRefStyle.Dot, ident(libNameStringFieldName));
			Expression optionsMapExpr = memberRef(expr(typeRef(MangledFunctionMapper.class)), MemberRefStyle.Dot, "DEFAULT_OPTIONS");
			interf.addDeclaration(new VariablesDeclaration(typeRef(NativeLibrary.class), new Declarator.DirectDeclarator(
				nativeLibFieldName,
				methodCall(
					expr(typeRef(NativeLibrary.class)),
					MemberRefStyle.Dot,
					"getInstance",
					libraryNameFieldExpr.clone(),
					optionsMapExpr.clone()
				)
			)).addModifiers(Modifier.Public, Modifier.Static, Modifier.Final));
			Expression nativeLibFieldExpr = memberRef(expr(libTypeRef.clone()), MemberRefStyle.Dot, ident(nativeLibFieldName));
				
			if (result.config.useJNADirectCalls) {
				interf.addDeclaration(new Function(Function.Type.StaticInit, null, null).setBody(block(
					stat(methodCall(
						expr(typeRef(Native.class)),
						MemberRefStyle.Dot,
						"register",
						libraryNameFieldExpr.clone()
					))
				)).addModifiers(Modifier.Static));
			} else {
				VariablesDeclaration instanceDecl = new VariablesDeclaration(libTypeRef, new Declarator.DirectDeclarator(
					librariesHub == null ? "INSTANCE" : library,
					cast(
						libTypeRef, 
						methodCall(
							expr(typeRef(Native.class)),
							MemberRefStyle.Dot,
							"loadLibrary",
							libraryNameFieldExpr.clone(),
							libClassLiteral,
							optionsMapExpr.clone()
						)
					)
				)).addModifiers(Modifier.Public, Modifier.Static, Modifier.Final);
				if (librariesHub != null) {
					librariesHub.addDeclaration(instanceDecl);
					librariesHub.addProtocol(fullLibraryClassName.clone());
				} else
					interf.addDeclaration(instanceDecl);
			}
			
			//out.println("\tpublic " + libraryClassName + " INSTANCE = (" + libraryClassName + ")" + Native.class.getName() + ".loadLibrary(" + libraryNameExpression  + ", " + libraryClassName + ".class);");
			
			Signatures signatures = result.getSignaturesForOutputClass(fullLibraryClassName);
			result.typeConverter.allowFakePointers = true;
			result.declarationsConverter.convertEnums(result.enumsByLibrary.get(library), signatures, interf, fullLibraryClassName);
			result.declarationsConverter.convertConstants(library, result.definesByLibrary.get(library), sourceFiles, signatures, interf, fullLibraryClassName);
			result.declarationsConverter.convertStructs(result.structsByLibrary.get(library), signatures, interf, fullLibraryClassName);
			result.declarationsConverter.convertCallbacks(result.callbacksByLibrary.get(library), signatures, interf, fullLibraryClassName);
			result.declarationsConverter.convertFunctions(result.functionsByLibrary.get(library), signatures, interf, fullLibraryClassName);
			
			result.globalsGenerator.convertGlobals(result.globalsByLibrary.get(library), signatures, interf, nativeLibFieldExpr, fullLibraryClassName, library);
			
			result.typeConverter.allowFakePointers = false;
			
			Set<String> fakePointers = result.fakePointersByLibrary.get(fullLibraryClassName);
			if (fakePointers != null)
			for (String fakePointerName : fakePointers) {
				if (fakePointerName.contains("::"))
					continue;
				
				Identifier fakePointer = ident(fakePointerName);
				if (!signatures.classSignatures.add(fakePointer))
					continue;
				
				Struct ptClass = result.declarationsConverter.publicStaticClass(fakePointer, ident(PointerType.class), Struct.Type.JavaClass, null);
				ptClass.addToCommentBefore("Pointer to unknown (opaque) type");
				ptClass.addDeclaration(new Function(Function.Type.JavaMethod, fakePointer, null,
					new Arg("pointer", typeRef(Pointer.class))
				).addModifiers(Modifier.Public).setBody(
					block(stat(methodCall("super", varRef("pointer")))))
				);
				ptClass.addDeclaration(new Function(Function.Type.JavaMethod, fakePointer, null)
				.addModifiers(Modifier.Public)
				.setBody(
					block(stat(methodCall("super")))
				));
				interf.addDeclaration(decl(ptClass));
			}
			
			interf = result.notifyBeforeWritingClass(fullLibraryClassName, interf, signatures);
			if (interf != null) {
				final PrintWriter out = result.classOutputter.getClassSourceWriter(fullLibraryClassName.toString());
				
				//out.println("///\n/// This file was autogenerated by JNAerator (http://jnaerator.googlecode.com/), \n/// a tool written by Olivier Chafik (http://ochafik.free.fr/).\n///");
				result.printJavaHeader(javaPackage, out);
				out.println(interf);
				out.close();
			}
		}
		if (hubOut != null) {
			hubOut.println(librariesHub.toString());
			hubOut.close();
		}
	}
	/// To be overridden
	public Result createResult(final ClassOutputter outputter) {
		return new Result(config, new ClassOutputter() {
			@Override
			public PrintWriter getClassSourceWriter(String className)
					throws IOException {
				PrintWriter w = outputter.getClassSourceWriter(className);
				return new PrintWriter(w) {
					StringBuilder bout = new StringBuilder();
					@Override
					public void print(String s) {
						escapeUnicode(s, bout);
						super.print(bout.toString());
					}
				};
			}
		});
	}
		
	public void jnaerationCore(SourceFiles sourceFiles, Result result, Feedback feedback) throws IOException, LexerException, RecognitionException {
		feedback.setStatus("Normalizing parsed code...");
		
		/// Perform Objective-C-specific pre-transformation (javadoc conversion for enums + find name of enums based on next sibling integer typedefs)
		sourceFiles.accept(new ObjectiveCToJavaPreScanner());

		/// Explode declarations to have only one direct declarator each
		sourceFiles.accept(new CToJavaPreScanner());
		
		/// Give sensible names to anonymous function signatures, structs, enums, unions, and move them up one level as typedefs
		sourceFiles.accept(new MissingNamesChooser(result));
		
		/// Move storage modifiers up to the storage
		sourceFiles.accept(new Scanner() {
			@Override
			protected void visitTypeRef(TypeRef tr) {
				super.visitTypeRef(tr);
				Element parent = tr.getParentElement();
				if (parent instanceof TypeRef) {// || parent instanceof VariablesDeclaration) {
					List<Modifier> stoMods = getStoMods(tr.getModifiers());
					if (stoMods != null) {
						List<Modifier> newMods = new ArrayList<Modifier>(tr.getModifiers());
						newMods.removeAll(stoMods);
						tr.setModifiers(newMods);
						((ModifiableElement)parent).addModifiers(stoMods);
					}
				}
			}
			public List<Modifier> getStoMods(List<Modifier> mods) {
				List<Modifier> ret = null;
				for (Modifier mod : mods) {
					if (mod.isA(Modifier.Kind.StorageClassSpecifier)) {
						if (ret == null)
							ret = new ArrayList<Modifier>();
						ret.add(mod);
					}
				}
				return ret;
			}
		});
		
		/// Build JavaDoc comments where applicable
		sourceFiles.accept(new JavaDocCreator(result));
		
		assert checkNoCycles(sourceFiles);
		
		//##################################################################
		//##### BEGINNING HERE, sourceFiles NO LONGER GETS MODIFIED ! ######
		//##################################################################
		
		if (feedback != null && !result.config.bridgeSupportFiles.isEmpty())
			feedback.setStatus("Parsing BridgeSupport files...");
		
		new BridgeSupportParser(result, sourceFiles).parseBridgeSupportFiles();
		
		/// Gather Objective-C classes
		sourceFiles.accept(result);
		result.rehabilitateWeakTypeDefs();
		
		result.chooseLibraryClasses(config.packageName, config.rootPackageName);
		
		//TODO resolve variables in visual studio projects
		//TODO Propagate unconvertible expressions, mark corresponding elements / trees as "to be commented out"
		
		/// Resolution's first pass : define relevant chained environment for each element
//		final DefinitionsVisitor definitions = new DefinitionsVisitor();
//		sourceFiles.accept(definitions);
		
		/// Resolve references of variables and types (map id -> type)
//		ResolutionScanner resolutions = new ResolutionScanner(definitions, originalOut);
//		sourceFiles.accept(resolutions);
		

		/// Filter unused symbols from implicitely included files
//		if (config.symbolsAccepter != null) {
//			originalOut.println("Filtering unused symbols");
//			UnusedScanner unused = new UnusedScanner(resolutions, config.symbolsAccepter, null);//originalOut);
//			sourceFiles.accept(unused);
//			unused.removeUnused(null);	
//		}
		
		
		/// Spit Objective-C classes out
		if (!result.classes.isEmpty()) {
			feedback.setStatus("Generating Objective-C classes...");
			result.objectiveCGenerator.generateObjectiveCClasses();
		}
		
		feedback.setStatus("Generating libraries...");
		
		if (result.libraries.size() == 1) {
			List<Define> list = result.definesByLibrary.get(null);
			if (list != null) {
				String lib = result.libraries.iterator().next();
				Result.getList(result.definesByLibrary, lib).addAll(list);
			}
		}
		
		generateLibraryFiles(sourceFiles, result);

		//if (config.verbose)
		for (String unknownType : result.typeConverter.unknownTypes) 
			System.out.println("Unknown Type: " + unknownType);
		
	}
	private boolean checkNoCycles(SourceFiles sourceFiles) {
		final HashSet<Integer> ids = new HashSet<Integer>(new Arg().getId());
		sourceFiles.accept(new Scanner() {
			@Override
			protected void visitElement(Element d) {
				if (d != null && !ids.add(d.getId()))
					throw new RuntimeException("Cycle : " + d);
				super.visitElement(d);
			}
		});
		return true;
	}

}

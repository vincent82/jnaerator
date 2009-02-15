/*
	Copyright (c) 2009 Olivier Chafik, All Rights Reserved
	
	This file is part of JNAerator (http://jnaerator.googlecode.com/).
	
	JNAerator is free software: you can redistribute it and/or modify
	it under the terms of the GNU General Public License as published by
	the Free Software Foundation, either version 3 of the License, or
	(at your option) any later version.
	
	JNAerator is distributed in the hope that it will be useful,
	but WITHOUT ANY WARRANTY; without even the implied warranty of
	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
	GNU General Public License for more details.
	
	You should have received a copy of the GNU General Public License
	along with JNAerator.  If not, see <http://www.gnu.org/licenses/>.
*/
package com.ochafik.lang.grammar.objcpp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.antlr.runtime.ANTLRReaderStream;
import org.antlr.runtime.CommonTokenStream;
import org.antlr.runtime.RecognitionException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runner.notification.Failure;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import static org.junit.Assert.*;

import com.ochafik.io.ReadText;
import com.ochafik.junit.ParameterizedWithDescription;
import com.ochafik.lang.grammar.DummyDebugEventListener;

@SuppressWarnings("unused")
@RunWith(ParameterizedWithDescription.class)
//@RunWith(Parameterized.class)
public class ObjCppParsingTests {
	Class<?> elementsTests = ObjCppElementsTests.class;
	
	enum TestOption {
		ParseAndPrettyPrint, ParseOnly, ParseMustFail
	};
	
	String string;
	TestOption testOption;
	public ObjCppParsingTests(String string, TestOption testOption) {
		this.string = string;
		this.testOption = testOption;
	}

	@Test
	public void test() throws RecognitionException, IOException {
		PrintStream originalOut = System.out;
		PrintStream originalErr = System.err;
		ByteArrayOutputStream bout = new ByteArrayOutputStream();
		PrintStream pout = new PrintStream(bout);
		System.setOut(pout);
		System.setErr(pout);
		
		boolean ok = false;
		List<? extends Declaration> decls = null;
		
		try {
			
			switch (testOption) {
			case ParseMustFail:
				try {
					decls = newParser(string).declaration().declarations;
					assertTrue("Expression should not have been parsed : " + string, false);
				} catch (Throwable t) {
					ok = true;
				}
				break;
			case ParseOnly:
			case ParseAndPrettyPrint:
				decls = newParser(string).declaration().declarations;
				assertNotNull(string, decls);
				if (testOption == TestOption.ParseAndPrettyPrint) {
					assertEquals(string, 1, decls.size());
					Declaration firstDecl = decls.get(0);
					assertNotNull(string, firstDecl);
					assertEquals(string, string.trim(), firstDecl.toString().trim());
				}
				ok = true;
				break;
			}
			
			for (Declaration decl : decls) {
				if (decl == null)
					continue;
					
				String declStr = decl.toString();
				Declaration clo = decl.clone();
				assertNotNull("Null clone !", clo);
				String cloStr = clo.toString();
				if (!declStr.equals(cloStr)) {
					clo = decl.clone();
				}
				assertEquals("Clone failed !", declStr, cloStr);
			}
		} finally {
			pout.flush();
			System.setOut(originalOut);
			System.setErr(originalErr);
			if (!ok) {
				System.out.println("IN:  " + string);
				try {
					System.out.println("OUT: " + decls);
					System.out.write(bout.toByteArray());
					System.out.println();
				} catch (Throwable t) {
					t.printStackTrace();
				}
			}
		}
		
	}
	
	@Parameters
	public static List<Object[]> readDataFromFile() throws IOException {
		List<String> lines = ReadText.readLines(ObjCppParsingTests.class.getClassLoader().getResource("com/ochafik/lang/grammar/objcpp/ObjCppTest.mm"));
		TestOption testOption = TestOption.ParseAndPrettyPrint;
		List<Object[]> data = new ArrayList<Object[]>();
		
		StringBuilder b = new StringBuilder();
		for (String line : lines) {
			String trl = line.trim().replaceAll("\\s+", " ");
			if (trl.equals("#pragma reversible"))
				testOption = TestOption.ParseAndPrettyPrint;
			else if (trl.equals("#pragma parse"))
				testOption = TestOption.ParseOnly;
			else if (trl.equals("#pragma fail"))
				testOption = TestOption.ParseMustFail;
			else if (trl.startsWith("#pragma "))
				System.err.println("Unknown #pragma : " + line);
			else if (trl.startsWith("#")) {
				// skip
			}
			else if (trl.equals("--")) {
				if (b.length() > 0) {
					data.add(new Object[] { b.toString(), testOption });
					b.delete(0, b.length());
				}
			} else {
				if (b.length() > 0)
					b.append('\n');
				b.append(line);
			}	
		}
		if (b.length() > 0)
			data.add(new Object[] { b.toString(), testOption });
		
		return data;
	}

	static ObjCppParser newParser(String s) throws IOException {
		return new ObjCppParser(
			new CommonTokenStream(
				new ObjCppLexer(
					new ANTLRReaderStream(new StringReader(s))
				)
			)
			//, new DummyDebugEventListener()
		);
	}
}

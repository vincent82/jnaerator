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

import com.ochafik.lang.grammar.objcpp.VariableStorage.StorageModifier;

public abstract class TypeMutator {
	public static TypeMutator 
		CONST_STAR = new TypeMutator() { @Override public TypeRef mutateType(TypeRef type) {
			type = new TypeRef.Pointer(type, StorageModifier.Pointer);
			type.addModifier("const");
			return type;
		}},
		STAR = new TypeMutator() { @Override public TypeRef mutateType(TypeRef type) {
			return new TypeRef.Pointer(type, StorageModifier.Pointer);
		}},
		AMPERSTAND = new TypeMutator() { @Override public TypeRef mutateType(TypeRef type) {
			return new TypeRef.Pointer(type, StorageModifier.Reference);
		}},
		CONST = new TypeMutator() { @Override public TypeRef mutateType(TypeRef type) {
			type.addModifier("const");
			return type;
		}},
		BRACKETS = new TypeMutator() { @Override public TypeRef mutateType(TypeRef type) {
			return new TypeRef.ArrayRef(type, Expression.EMPTY_EXPRESSION);
		}}
	;
	public abstract TypeRef mutateType(TypeRef type);
	public static TypeMutator array(final Expression expression) {
		return new TypeMutator() { @Override public TypeRef mutateType(TypeRef type) {
			return new TypeRef.ArrayRef(type, expression);
		}};
	}
}

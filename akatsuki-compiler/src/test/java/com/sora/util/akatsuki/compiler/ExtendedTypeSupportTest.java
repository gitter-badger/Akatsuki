package com.sora.util.akatsuki.compiler;

import android.os.Parcelable;

import com.sora.util.akatsuki.Retained;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class ExtendedTypeSupportTest extends CodeGenerationTestBase {

	@Test
	public void testArrayTypes() {
		testSimpleTypes(n -> true, TestEnvironment.CLASS, null, String[].class);
		testSimpleTypes(n -> true, TestEnvironment.CLASS,
				f -> field(f.typeName(), "a", Retained.class), String[][][][][].class);


		testTypes(n -> true, TestEnvironment.CLASS, f -> field(f.typeName(), "b", Retained.class),
				new Field(ArrayList[].class, String.class));
	}

	@Test
	public void testListInterface() {
		testParameterizedTypes(n -> n.contains("ArrayList"),
				(f, t, a) -> t.equals(ArrayList.class) && Arrays.equals(a, f.parameters),
				f -> field(f.typeName(), f.name, Retained.class, "new java.util.ArrayList<>()"),
				List.class, Parcelable.class);
	}

	@Test
	public void testArrayListTypes() {
		for (Class<?> clazz : Arrays.asList(LinkedList.class, CopyOnWriteArrayList.class)) {
			testParameterizedTypes(n -> n.contains("ArrayList"),
					(f, t, a) -> t.equals(ArrayList.class) && Arrays.equals(a, f.parameters),
					f -> field(f.typeName(), f.name, Retained.class,
							"new " + clazz.getName() + "<>()"),
					clazz, Parcelable.class);

		}
	}

}

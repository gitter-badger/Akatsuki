package com.sora.util.akatsuki.compiler.transformations;

import com.google.common.base.MoreObjects;
import com.google.common.base.Strings;
import com.google.common.escape.CharEscaperBuilder;
import com.google.common.escape.Escaper;
import com.sora.util.akatsuki.Akatsuki.LoggingLevel;
import com.sora.util.akatsuki.compiler.AkatsukiProcessor;
import com.sora.util.akatsuki.compiler.BundleContext;
import com.sora.util.akatsuki.compiler.MustacheUtils;
import com.sora.util.akatsuki.compiler.ProcessorContext;
import com.sora.util.akatsuki.compiler.ProcessorElement;
import com.sora.util.akatsuki.compiler.ProcessorUtils;
import com.sora.util.akatsuki.compiler.transformations.CascadingTypeAnalyzer.Analysis;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.annotation.processing.Messager;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic.Kind;

public abstract class CascadingTypeAnalyzer<S extends CascadingTypeAnalyzer<S, T, A>, T extends TypeMirror, A extends Analysis>
		implements TransformationContext {

	private int cascadeDepth;

	public enum InvocationType {
		SAVE, RESTORE
	}

	public static final Escaper ESCAPER = new CharEscaperBuilder()
			.addEscapes(new char[] { '[', ']', '.', '(', ')' }, "_").toEscaper();

	private final TransformationContext context;

	protected TypeMirror targetMirror;
	protected TypeCastStrategy strategy = TypeCastStrategy.AUTO_CAST;
	protected String suffix = "";

	public S target(TypeMirror mirror) {
		final S instance = createInstance(context);
		cloneFields(instance);
		instance.targetMirror = mirror;
		return instance;
	}

	public S cast(TypeCastStrategy strategy) {
		final S instance = createInstance(context);
		cloneFields(instance);
		instance.strategy = strategy;
		return instance;
	}

	public S suffix(String suffix) {
		final S instance = createInstance(context);
		cloneFields(instance);
		instance.suffix = this.suffix + suffix;
		return instance;
	}

	private void cloneFields(CascadingTypeAnalyzer<?, ?, ?> target) {
		target.targetMirror = this.targetMirror;
		target.suffix = this.suffix;
		target.strategy = this.strategy;
	}

	protected abstract S createInstance(TransformationContext context);

	public enum TypeCastStrategy {
		NO_CAST((context, from, to) -> null), //
		FORCE_CAST((context, from, to) -> to.toString()), //
		AUTO_CAST((context, from, to) -> context.utils().isAssignable(from, to, true) ? null
				: to.toString());//

		private final ClassCastFunction function;

		TypeCastStrategy(ClassCastFunction function) {
			this.function = function;
		}

		public interface ClassCastFunction {
			String createCastExpression(ProcessorContext context, TypeMirror from, TypeMirror to);
		}

	}

	public CascadingTypeAnalyzer(TransformationContext context) {
		this.context = context;
		this.cascadeDepth = 0;
	}

	public static class InvocationContext<T extends TypeMirror> {
		public final BundleContext bundleContext;
		public final ProcessorElement<T> field;
		public final InvocationType type;

		public InvocationContext(BundleContext bundleContext, ProcessorElement<T> field,
				InvocationType type) {
			this.bundleContext = bundleContext;
			this.field = field;
			this.type = type;
		}
	}

	protected abstract A createAnalysis(InvocationContext<T> context) throws UnknownTypeException;

	protected String fieldAccessor(InvocationContext<?> context) {
		return context.field.accessor(fn -> context.bundleContext.sourceObjectName() + "." + fn);
	}

	@SuppressWarnings("unchecked")
	public A transform(BundleContext bundleContext, ProcessorElement<?> element,
			InvocationType type) throws UnknownTypeException {
		if (AkatsukiProcessor.retainConfig().loggingLevel() == LoggingLevel.VERBOSE) {
			context.messager().printMessage(Kind.OTHER,
					type + ">" + cascadeDepth + Strings.repeat(" ", cascadeDepth) + "`Cascade:"
							+ toString() + " -> " + element.toString() + " with " + bundleContext);
		}
		return createAnalysis(
				new InvocationContext<>(bundleContext, (ProcessorElement<T>) element, type));
	}

	public Analysis cascade(CascadingTypeAnalyzer<?, ?, ?> transformation,
			InvocationContext<?> context, TypeMirror mirror) throws UnknownTypeException {
		return cascade(transformation, context, f -> f.refine(mirror));
	}

	public Analysis cascade(CascadingTypeAnalyzer<?, ?, ?> transformation,
			InvocationContext<?> context,
			Function<ProcessorElement<?>, ProcessorElement<?>> elementTransformation)
					throws UnknownTypeException {
		transformation.cascadeDepth = cascadeDepth + 1;
		return transformation.transform(context.bundleContext,
				elementTransformation.apply(context.field), context.type);

	}

	protected TypeMirror targetOrElse(TypeMirror mirror) {
		return targetMirror != null ? targetMirror : mirror;
	}

	@Override
	public Types types() {
		return context.types();
	}

	@Override
	public Elements elements() {
		return context.elements();
	}

	@Override
	public ProcessorUtils utils() {
		return context.utils();
	}

	@Override
	public Messager messager() {
		return context.messager();
	}

	@Override
	public CascadingTypeAnalyzer<?, ? extends TypeMirror, ? extends Analysis> resolve(
			ProcessorElement<?> element) {
		return context.resolve(element);
	}

	public interface Analysis {

		void prependOnce(Analysis analysis);

		void appendOnce(Analysis analysis);

		void prependOnce(String string);

		void appendOnce(String string);

		String emit();

		void transform(CodeTransform transform);

		void wrap(CodeTransform transformation);

		String preEmitOnce();

		String postEmitOnce();

	}

	@Override
	public String toString() {
		return MoreObjects.toStringHelper(this).add("targetMirror", targetMirror)
				.add("strategy", strategy).add("suffix", suffix).toString();
	}

	static abstract class ChainedAnalysis implements Analysis {

		private final List<String> preEmit = new ArrayList<>();
		private final List<String> postEmit = new ArrayList<>();
		protected final CodeTransforms transforms = new CodeTransforms();

		@Override
		public void prependOnce(Analysis analysis) {
			appendAnalysis(preEmit, analysis);
		}

		@Override

		public void appendOnce(Analysis analysis) {
			appendAnalysis(postEmit, analysis);

		}

		@Override
		public void wrap(CodeTransform transform) {
			transforms.append(transform);
		}

		@Override
		public void prependOnce(String string) {
			preEmit.add(string);
		}

		@Override
		public void appendOnce(String string) {
			postEmit.add(string);
		}

		@Override
		public String preEmitOnce() {
			return preEmit.stream().collect(Collectors.joining());
		}

		@Override
		public String postEmitOnce() {
			return postEmit.stream().collect(Collectors.joining());
		}

		private void appendAnalysis(List<String> list, Analysis analysis) {
			list.add(analysis.preEmitOnce());
			list.add(analysis.emit());
			list.add(analysis.postEmitOnce());
		}

	}

	static class DefaultAnalysis extends ChainedAnalysis {

		private final Map<String, Object> scope;
		private RawExpression expression;

		public interface RawExpression {

			String render(Object scope);

			void transform(CodeTransform transformation);

		}

		public static class InvocationExpression implements RawExpression {

			private String template;

			public InvocationExpression(String template) {
				this.template = template;
			}

			@Override
			public String render(Object scope) {
				return MustacheUtils.render(scope, template) + ";\n";
			}

			@Override
			public void transform(CodeTransform transform) {
				template = transform.apply(template);
			}
		}

		public static class InvocationAssignmentExpression extends InvocationExpression {

			private final String variable;

			public InvocationAssignmentExpression(String variable, String template) {
				super(template);
				this.variable = variable;
			}

			@Override
			public String render(Object scope) {
				return MustacheUtils.render(scope, variable) + " = " + super.render(scope);
			}
		}

		DefaultAnalysis(Map<String, Object> scope, RawExpression expression) {
			this.scope = scope;
			this.expression = expression;
		}

		@Override
		public String emit() {
			return transforms.apply(expression.render(scope));
		}

		@Override
		public void transform(CodeTransform transform) {
			expression.transform(transform);
		}

		static Map<String, Object> createScope(ProcessorElement<?> element,
				BundleContext bundleContext) {
			final HashMap<String, Object> map = new HashMap<>();
			map.put("fieldName",
					element.accessor(fn -> bundleContext.sourceObjectName() + "." + fn));
			map.put("keyName", element.keyName());
			map.put("bundle", bundleContext.bundleObjectName());
			return map;
		}

		static <T extends TypeMirror> DefaultAnalysis of(CascadingTypeAnalyzer<?, T, ?> analyzer,
				String methodName, InvocationContext<T> context) {
			final HashMap<String, Object> scope = new HashMap<>();
			scope.put("fieldName", context.field
					.accessor(fn -> context.bundleContext.sourceObjectName() + "." + fn));
			scope.put("keyName", context.field.keyName());
			scope.put("bundle", context.bundleContext.bundleObjectName());
			scope.put("methodName", methodName);

			RawExpression expression;
			if (context.type == InvocationType.SAVE) {
				// field mirror -> target mirror , we don't need to cast
				expression = new InvocationExpression(
						"{{bundle}}.put{{methodName}}({{keyName}}, {{fieldName}})");
			} else {
				if (analyzer.targetMirror != null) {
					// when restore, target mirror is the return mirror so we
					// cast in opposite detection: target mirror -> field mirror
					final String castExpression = analyzer.strategy.function.createCastExpression(
							analyzer, analyzer.targetMirror, context.field.fieldMirror());
					if (!Strings.isNullOrEmpty(castExpression)) {
						scope.put("cast", true);
						scope.put("castExpression", castExpression);
					}
				}
				expression = new InvocationAssignmentExpression("{{fieldName}}",
						"{{#cast}}({{castExpression}}){{/cast}}{{bundle}}.get{{methodName}}({{keyName}})");
			}
			return new DefaultAnalysis(scope, expression);
		}

	}

	public static class ConversionException extends RuntimeException {

		public ConversionException(String message) {
			super(message);
		}

		public ConversionException(String message, Throwable cause) {
			super(message, cause);
		}
	}

	public static class UnknownTypeException extends ConversionException {

		// public final Field<?> field;

		public UnknownTypeException(ProcessorElement<?> element) {
			super("unknown type " + element + "(" + element.refinedMirror().getClass() + ")");
		}

		public UnknownTypeException(TypeMirror mirror) {
			super("unknown type " + mirror + "(" + mirror.getClass() + ")");
		}

	}

	@FunctionalInterface
	public interface CodeTransform extends Function<String, String> {

	}

	public static class CodeTransforms {
		private final ArrayList<CodeTransform> transforms = new ArrayList<>();

		public void append(CodeTransform transform) {
			this.transforms.add(transform);
		}

		public String apply(String source) {
			for (CodeTransform transform : transforms) {
				source = transform.apply(source);
			}
			return source;
		}
	}

}

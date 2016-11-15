package org.grails.compiler.gorm

import grails.gorm.dirty.checking.DirtyCheckedProperty
import groovy.transform.CompilationUnitAware
import groovy.transform.CompileStatic
import org.codehaus.groovy.ast.*
import org.codehaus.groovy.ast.expr.*
import org.codehaus.groovy.ast.stmt.*
import org.codehaus.groovy.classgen.GeneratorContext
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.syntax.Token
import org.codehaus.groovy.syntax.Types
import org.codehaus.groovy.transform.sc.StaticCompilationVisitor
import org.grails.datastore.mapping.dirty.checking.DirtyCheckable
import org.grails.datastore.mapping.model.config.GormProperties
import org.grails.datastore.mapping.reflect.AstUtils
import org.grails.datastore.mapping.reflect.NameUtils
import org.grails.datastore.mapping.reflect.ReflectionUtils

import static java.lang.reflect.Modifier.*
import static org.grails.datastore.mapping.reflect.AstUtils.*
/**
 *
 * Transforms a domain class making it possible for the domain class to take responsibility of tracking changes to itself, thus removing the responsibility from the ORM system which would have to maintain parallel state
 * and compare the state of the domain class to the stored state. With this transformation the storage of the state is not necessary as the state is kept in the domain class itself
 *
 * @author Graeme Rocher
 * @since 2.0
 */
@CompileStatic
class DirtyCheckingTransformer implements CompilationUnitAware {
    private static final String VOID = "void";
    private static final Class<?>[] EMPTY_JAVA_CLASS_ARRAY = [];
    private static final Class<?>[] OBJECT_CLASS_ARG = [Object.class];
    public static final String METHOD_NAME_MARK_DIRTY = "markDirty"
    public static final ConstantExpression CONSTANT_NULL = new ConstantExpression(null)
    public static final ClassNode DIRTY_CHECKED_PROPERTY_CLASS_NODE = ClassHelper.make(DirtyCheckedProperty)
    public static final AnnotationNode DIRTY_CHECKED_PROPERTY_ANNOTATION_NODE = new AnnotationNode(DIRTY_CHECKED_PROPERTY_CLASS_NODE)


    protected CompilationUnit compilationUnit

    void setCompilationUnit(CompilationUnit compilationUnit) {
        this.compilationUnit = compilationUnit
    }

    void performInjectionOnAnnotatedClass(SourceUnit source, ClassNode classNode) {
        // First add a local field that will store the change tracking state. The field is a simple list of property names that have changed
        // the field is only added to root clauses that extend from java.lang.Object
        final changeTrackableClassNode = new ClassNode(DirtyCheckable).getPlainNodeReference()
        final markDirtyMethodNode = changeTrackableClassNode.getMethod(METHOD_NAME_MARK_DIRTY, new Parameter(ClassHelper.STRING_TYPE, "propertyName"), new Parameter(ClassHelper.OBJECT_TYPE, "newValue"))


        def superClass = classNode.getSuperClass()
        final shouldWeave = superClass.equals(OBJECT_CLASS_NODE)

        def dirtyCheckableTrait = ClassHelper.make(DirtyCheckable).getPlainNodeReference()

        if(!shouldWeave) {
            shouldWeave = !classNode.implementsInterface(dirtyCheckableTrait)
        }

        if(shouldWeave ) {

            classNode.addInterface(dirtyCheckableTrait)
            if(compilationUnit != null) {
                org.codehaus.groovy.transform.trait.TraitComposer.doExtendTraits(classNode, source, compilationUnit);
            }

        }

        // Now we go through all the properties, if the property is a persistent property and change tracking has been initiated then we add to the setter of the property
        // code that will mark the property as dirty. Note that if the property has no getter we have to add one, since only adding the setter results in a read-only property
        final propertyNodes = classNode.getProperties()
        def staticCompilationVisitor = new StaticCompilationVisitor(source, classNode)
        Map<String, GetterAndSetter> gettersAndSetters = [:]

        for (MethodNode mn in classNode.methods) {
            final methodName = mn.name
            if(!mn.isPublic() || mn.isStatic() || mn.isSynthetic() || mn.isAbstract()) continue

            if (isSetter(methodName, mn)) {
                String propertyName = NameUtils.getPropertyNameForGetterOrSetter(methodName)
                GetterAndSetter getterAndSetter = getGetterAndSetterForPropertyName(gettersAndSetters, propertyName)
                getterAndSetter.setter = mn
            } else if (isGetter(methodName, mn)) {
                String propertyName = NameUtils.getPropertyNameForGetterOrSetter(methodName)
                GetterAndSetter getterAndSetter = getGetterAndSetterForPropertyName(gettersAndSetters, propertyName)
                getterAndSetter.getter = mn
            }
        }

        for (PropertyNode pn in propertyNodes) {
            final propertyName = pn.name
            if (!pn.isStatic() && pn.isPublic() && !NameUtils.isConfigurational(propertyName)) {
                if(isTransient(pn.modifiers) || isFinal(pn.modifiers)) continue

                // don't dirty check id or version
                if(propertyName == GormProperties.IDENTITY || propertyName == GormProperties.VERSION) {
                    continue
                }

                final getterAndSetter = gettersAndSetters[propertyName]

                // if there is no explicit getter and setter then one will be generated by Groovy, so we must add these to track changes
                if(getterAndSetter == null) {
                    final propertyField = pn.getField()

                    // first add the getter
                    ClassNode originalReturnType = pn.getType()
                    ClassNode returnType;
                    if(!originalReturnType.getNameWithoutPackage().equals(VOID)) {
                        if(ClassHelper.isPrimitiveType(originalReturnType.redirect())) {
                            returnType = originalReturnType.getPlainNodeReference()
                        } else {
                            returnType = alignReturnType(classNode, originalReturnType);
                        }
                    }
                    else {
                        returnType = originalReturnType
                    }
                    boolean booleanProperty = ClassHelper.boolean_TYPE.getName().equals(returnType.getName())
                    def getter = classNode.addMethod(NameUtils.getGetterName(propertyName, false), PUBLIC, returnType, ZERO_PARAMETERS, null, new ReturnStatement(new VariableExpression(propertyField.getName())))
                    getter.addAnnotation(DIRTY_CHECKED_PROPERTY_ANNOTATION_NODE)
                    staticCompilationVisitor.visitMethod(
                        getter
                    )

                    if(booleanProperty) {
                        classNode.addMethod(NameUtils.getGetterName(propertyName, true), PUBLIC, returnType, ZERO_PARAMETERS, null, new ReturnStatement(new VariableExpression(propertyField.getName())))
                    }

                    // now add the setter that tracks changes. Each setters becomes:
                    // void setFoo(String foo) { markDirty("foo", foo); this.foo = foo }
                    final setterName = NameUtils.getSetterName(propertyName)
                    final setterParameter = new Parameter(returnType, propertyName)
                    final setterBody = new BlockStatement()
                    MethodCallExpression markDirtyMethodCall = createMarkDirtyMethodCall(markDirtyMethodNode, propertyName, setterParameter)
                    setterBody.addStatement(new ExpressionStatement(markDirtyMethodCall))
                    setterBody.addStatement(  new ExpressionStatement(
                            new BinaryExpression(new PropertyExpression(new VariableExpression("this"), propertyField.name),
                                    Token.newSymbol(Types.EQUAL, 0, 0),
                                    new VariableExpression(setterParameter))
                    )
                    )


                    def setter = classNode.addMethod(setterName, PUBLIC, ClassHelper.VOID_TYPE, [setterParameter] as Parameter[], null, setterBody)
                    setter.addAnnotation(DIRTY_CHECKED_PROPERTY_ANNOTATION_NODE)
                    staticCompilationVisitor.visitMethod(
                        setter
                    )

                }
                else if(getterAndSetter.hasBoth()) {
                    // if both a setter and getter are present, we get hold of the setter and weave the markDirty method call into it
                    weaveIntoExistingSetter(propertyName, getterAndSetter, markDirtyMethodNode)
                }
                else {
                    // there isn't both a getter and a setter then this is not a candidate for persistence, so we eliminate it from change tracking
                    gettersAndSetters.remove(propertyName)
                }
            }
        }

        // We also need to search properties that are represented as getters with setters. This requires going through all the methods and finding getter/setter pairs that are public
        gettersAndSetters.each { String propertyName, GetterAndSetter getterAndSetter ->
            if(!NameUtils.isConfigurational(propertyName) && getterAndSetter.hasBoth()) {
                weaveIntoExistingSetter(propertyName, getterAndSetter, markDirtyMethodNode)
            }
        }
    }

    void performInjection(SourceUnit source, GeneratorContext context, ClassNode classNode) {
        if (classNode.annotations.any { AnnotationNode an -> an.classNode.name == 'grails.artefact.Artefact'}) return;

        performInjectionOnAnnotatedClass(source, classNode)
    }

    void performInjection(SourceUnit source, ClassNode classNode) {
        performInjection(source, null, classNode)
    }

    public boolean shouldInject(URL url) {
        return AstUtils.isDomainClass(url);
    }

    void performInjectionOnAnnotatedEntity(ClassNode classNode) {
        performInjectionOnAnnotatedClass(null, classNode)
    }


    private static ClassNode alignReturnType(final ClassNode receiver, final ClassNode originalReturnType) {
        ClassNode copiedReturnType
        if(originalReturnType.isGenericsPlaceHolder()) {
            copiedReturnType = originalReturnType.getPlainNodeReference();
            copiedReturnType.setName( originalReturnType.getName() )
            copiedReturnType.setGenericsPlaceHolder(true)
        }
        else {
            copiedReturnType = originalReturnType.getPlainNodeReference();
        }

        final genericTypes = originalReturnType.getGenericsTypes()
        if (genericTypes) {
            List<GenericsType> newGenericTypes = []

            for(GenericsType gt in genericTypes) {
                ClassNode[] upperBounds = null
                if (gt.upperBounds) {
                    upperBounds = gt.upperBounds.collect { ClassNode cn -> cn.plainNodeReference } as ClassNode[]
                }
                newGenericTypes << new GenericsType(gt.type.plainNodeReference, upperBounds, gt.lowerBound?.plainNodeReference)
            }
            copiedReturnType.setGenericsTypes(newGenericTypes as GenericsType[])
        }
        return copiedReturnType;
    }
    protected void weaveIntoExistingSetter(String propertyName, GetterAndSetter getterAndSetter, MethodNode markDirtyMethodNode) {
        final setterMethod = getterAndSetter.setter
        if(setterMethod.annotations.any { AnnotationNode an -> an.classNode.name == 'grails.persistence.PersistenceMethod'} ) return

        if(!setterMethod.getAnnotations(DIRTY_CHECKED_PROPERTY_CLASS_NODE)) {
            setterMethod.addAnnotation(DIRTY_CHECKED_PROPERTY_ANNOTATION_NODE)
        }
        def getter = getterAndSetter.getter
        if(!getter.getAnnotations(DIRTY_CHECKED_PROPERTY_CLASS_NODE)) {
            getter.addAnnotation(DIRTY_CHECKED_PROPERTY_ANNOTATION_NODE)
        }
        final currentBody = setterMethod.code
        final setterParameter = setterMethod.getParameters()[0]
        MethodCallExpression markDirtyMethodCall = createMarkDirtyMethodCall(markDirtyMethodNode, propertyName, setterParameter)
        final newBody = new BlockStatement()
        newBody.addStatement(new ExpressionStatement(markDirtyMethodCall))
        newBody.addStatement(currentBody)
        setterMethod.code = newBody
    }

    protected MethodCallExpression createMarkDirtyMethodCall(MethodNode markDirtyMethodNode, String propertyName, Variable value) {
        def args = new ArgumentListExpression(new ConstantExpression(propertyName), new VariableExpression(value))
        final markDirtyMethodCall = new MethodCallExpression(new VariableExpression("this"), markDirtyMethodNode.name, args)
        markDirtyMethodCall.methodTarget = markDirtyMethodNode
        markDirtyMethodCall
    }

    protected GetterAndSetter getGetterAndSetterForPropertyName(LinkedHashMap<String, GetterAndSetter> gettersAndSetters, String propertyName) {
        def getterAndSetter = gettersAndSetters[propertyName]
        if (getterAndSetter == null) {
            getterAndSetter = new GetterAndSetter()
            gettersAndSetters[propertyName] = getterAndSetter
        }
        getterAndSetter
    }



    private boolean isSetter(String methodName, MethodNode declaredMethod) {
        return declaredMethod.getParameters().length == 1 && ReflectionUtils.isSetter(methodName, OBJECT_CLASS_ARG);
    }

    private boolean isGetter(String methodName, MethodNode declaredMethod) {
        return declaredMethod.getParameters().length == 0 && ReflectionUtils.isGetter(methodName, EMPTY_JAVA_CLASS_ARRAY);
    }

    String[] getArtefactTypes() {
        return ["Domain"] as String[];
    }

    @CompileStatic
    class GetterAndSetter {
        MethodNode getter
        MethodNode setter

        boolean hasBoth() {
            getter && setter
        }

        boolean hasNeither() {
            !getter && !setter
        }
    }
}

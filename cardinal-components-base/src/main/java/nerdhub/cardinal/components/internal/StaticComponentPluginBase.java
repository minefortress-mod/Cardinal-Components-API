/*
 * Cardinal-Components-API
 * Copyright (C) 2019-2020 OnyxStudios
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
 * DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE
 * OR OTHER DEALINGS IN THE SOFTWARE.
 */
package nerdhub.cardinal.components.internal;

import nerdhub.cardinal.components.api.ComponentType;
import nerdhub.cardinal.components.api.component.Component;
import nerdhub.cardinal.components.api.component.ComponentContainer;
import nerdhub.cardinal.components.api.component.ComponentProvider;
import nerdhub.cardinal.components.api.component.StaticComponentInitializer;
import nerdhub.cardinal.components.api.event.ComponentCallback;
import nerdhub.cardinal.components.api.util.container.FastComponentContainer;
import nerdhub.cardinal.components.internal.asm.CcaAsmHelper;
import nerdhub.cardinal.components.internal.asm.StaticComponentLoadingException;
import net.fabricmc.fabric.api.event.Event;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;

import javax.annotation.Nullable;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;

public abstract class StaticComponentPluginBase<T, I extends StaticComponentInitializer, F> extends DispatchingLazy {
    private static final String FOR_EACH_DESC;
    private static final String FAST_COMPONENT_CONTAINER_CTOR_DESC;
    private static final String GET_DESC;
    private static final String CAN_BE_ASSIGNED_DESC;

    private static final String EVENT_NAME = Type.getInternalName(Event.class);
    private static final String EVENT_DESC = Type.getDescriptor(Event.class);
    private static final String EVENT$INVOKER_DESC;

    static {
        try {
            FOR_EACH_DESC = Type.getMethodDescriptor(ComponentContainer.class.getMethod("forEach", BiConsumer.class));
            FAST_COMPONENT_CONTAINER_CTOR_DESC = Type.getConstructorDescriptor(FastComponentContainer.class.getConstructor(int.class));
            GET_DESC = Type.getMethodDescriptor(ComponentContainer.class.getMethod("get", ComponentType.class));
            CAN_BE_ASSIGNED_DESC = Type.getMethodDescriptor(FastComponentContainer.class.getDeclaredMethod("canBeAssigned", ComponentType.class));
            EVENT$INVOKER_DESC = Type.getMethodDescriptor(Event.class.getMethod("invoker"));
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Failed to find one or more method descriptors", e);
        }
    }

    private final Class<? super F> componentFactoryType;
    private final Map<Identifier, F> componentFactories = new LinkedHashMap<>();
    private final Class<T> providerClass;
    private final String implSuffix;
    private final Class<I> initializerType;
    protected final MethodHandles.Lookup lookup = MethodHandles.publicLookup();
    private Class<? extends DynamicContainerFactory<T,?>> containerFactoryClass;

    protected StaticComponentPluginBase(Class<T> providerClass, Class<? super F> componentFactoryType, Class<I> initializerType, String implSuffix) {
        this.componentFactoryType = componentFactoryType;
        this.providerClass = providerClass;
        this.implSuffix = implSuffix;
        this.initializerType = initializerType;
    }

    /**
     * Defines an implementation of {@link ComponentContainer} that supports direct component access.
     *
     * <p>Instances of the returned class can be returned by {@link ComponentProvider#getStaticComponentContainer()}.
     * <strong>This method must not be called before the static component container interface has been defined!</strong>
     *
     * <p>Generated component container classes will take an additional {@code int} as first argument to their
     * constructors. That number corresponds to the expected dynamic size of the container (see {@link FastComponentContainer}).
     *
     *
     * @param componentFactoryType the interface implemented by the component factories used to initialize this container
     * @param componentFactories a map of {@link ComponentType} ids to factories for components of that type
     * @param implNameSuffix a unique suffix for the generated class
     * @return the generated container class
     */
    public static <I> Class<? extends ComponentContainer<?>> spinComponentContainer(Class<? super I> componentFactoryType, Map<Identifier, I> componentFactories, String implNameSuffix) throws IOException {
        checkValidJavaIdentifier(implNameSuffix);
        String containerImplName = CcaAsmHelper.STATIC_COMPONENT_CONTAINER + '_' + implNameSuffix;
        String componentFactoryName = Type.getInternalName(componentFactoryType);
        Method sam = CcaAsmHelper.findSam(componentFactoryType);
        String samDescriptor = Type.getMethodDescriptor(sam);
        Class<?>[] factoryArgs = sam.getParameterTypes();
        Type[] actualCtorArgs = new Type[factoryArgs.length + 1];
        actualCtorArgs[0] = Type.INT_TYPE;
        for (int i = 0; i < factoryArgs.length; i++) {
            actualCtorArgs[i+1] = Type.getType(factoryArgs[i]);
        }
        String ctorDesc = Type.getMethodDescriptor(Type.VOID_TYPE, actualCtorArgs);
        int ctorFirstAvailableVar = actualCtorArgs.length + 1;  // explicit args + <this>
        ClassNode classNode = new ClassNode(CcaAsmHelper.ASM_VERSION);
        classNode.visit(
            Opcodes.V1_8,
            Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
            containerImplName,
            null,
            CcaAsmHelper.DYNAMIC_COMPONENT_CONTAINER_IMPL,
            new String[] {CcaAsmHelper.STATIC_COMPONENT_CONTAINER}
        );
        MethodVisitor init = classNode.visitMethod(Opcodes.ACC_PUBLIC, "<init>", ctorDesc, null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitVarInsn(Opcodes.ILOAD, 1);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, CcaAsmHelper.DYNAMIC_COMPONENT_CONTAINER_IMPL, "<init>", FAST_COMPONENT_CONTAINER_CTOR_DESC, false);
        MethodVisitor get = classNode.visitMethod(Opcodes.ACC_PUBLIC, "get", GET_DESC, null, null);
        MethodVisitor forEach = classNode.visitMethod(Opcodes.ACC_PUBLIC, "forEach", FOR_EACH_DESC, null, null);
        MethodVisitor canBeAssigned = classNode.visitMethod(Opcodes.ACC_PROTECTED, "canBeAssigned", CAN_BE_ASSIGNED_DESC, null, null);
        Label canBeAssignedFalse = new Label();
        for (Identifier identifier : componentFactories.keySet()) {
            String fieldName = CcaAsmHelper.getJavaIdentifierName(identifier);
            String factoryFieldName = getFactoryFieldName(identifier);
            String fieldDescriptor = Type.getDescriptor(Component.class);
            String factoryFieldDescriptor = Type.getDescriptor(componentFactoryType);
            /* field declaration */
            classNode.visitField(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                factoryFieldName,
                factoryFieldDescriptor,
                null,
                null
            ).visitEnd();
            classNode.visitField(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
                fieldName,
                fieldDescriptor,
                null,
                null
            ).visitEnd();
            /* constructor initialization */
            init.visitFieldInsn(Opcodes.GETSTATIC, containerImplName, factoryFieldName, factoryFieldDescriptor);
            // stack: factory
            for (int i = 0; i < factoryArgs.length; i++) {
                init.visitVarInsn(Opcodes.ALOAD, i + 2);    // first 2 args are for the container itself
            }
            // stack: factory factoryArgs...
            // initialize the component by calling the factory
            init.visitMethodInsn(Opcodes.INVOKEINTERFACE, componentFactoryName, sam.getName(), samDescriptor, true);
            // stack: component
            init.visitInsn(Opcodes.DUP);
            // stack: component component
            init.visitVarInsn(Opcodes.ASTORE, ctorFirstAvailableVar);
            // stack: component
            // if the factory method returned null, we just ignore it
            Label nextInit = new Label();
            init.visitJumpInsn(Opcodes.IFNULL, nextInit);
            // <empty stack>
            init.visitVarInsn(Opcodes.ALOAD, 0);
            init.visitInsn(Opcodes.DUP);
            init.visitVarInsn(Opcodes.ALOAD, ctorFirstAvailableVar);
            // stack: <this> <this> component
            // store in the field
            init.visitFieldInsn(Opcodes.PUTFIELD, containerImplName, fieldName, fieldDescriptor);
            // stack: <this>
            stackStaticComponentType(init, identifier);
            // stack: <this> componentType
            init.visitMethodInsn(Opcodes.INVOKEVIRTUAL, CcaAsmHelper.DYNAMIC_COMPONENT_CONTAINER_IMPL, "addContainedType", "(L" + CcaAsmHelper.COMPONENT_TYPE + ";)V", false);
            // <empty stack>
            init.visitLabel(nextInit);

            /* get implementation */
            // note: this implementation is O(n). For best results, we could make a big lookup table based on
            Label nextGet = new Label();
            get.visitVarInsn(Opcodes.ALOAD, 1);
            stackStaticComponentType(get, identifier);
            get.visitJumpInsn(Opcodes.IF_ACMPNE, nextGet);
            stackStaticComponent(get, containerImplName, fieldName, fieldDescriptor);
            get.visitInsn(Opcodes.ARETURN);
            get.visitLabel(nextGet);

            /* forEach implementation */
            forEach.visitVarInsn(Opcodes.ALOAD, 1);
            // stack: biConsumer
            stackStaticComponentType(forEach, identifier);
            // stack: biConsumer componentType
            stackStaticComponent(forEach, containerImplName, fieldName, fieldDescriptor);
            // stack: biConsumer componentType component
            forEach.visitMethodInsn(Opcodes.INVOKEINTERFACE, Type.getInternalName(BiConsumer.class), "accept", "(Ljava/lang/Object;Ljava/lang/Object;)V", true);

            /* canBeAssigned implementation */
            canBeAssigned.visitVarInsn(Opcodes.ALOAD, 1);
            // stack: componentType
            stackStaticComponentType(canBeAssigned, identifier);
            // stack: componentType componentType2
            canBeAssigned.visitJumpInsn(Opcodes.IF_ACMPEQ, canBeAssignedFalse);
            // <empty stack>

            /* getter implementation */
            MethodVisitor getter = classNode.visitMethod(
                Opcodes.ACC_PUBLIC,
                CcaAsmHelper.getStaticStorageGetterName(identifier),
                CcaAsmHelper.STATIC_CONTAINER_GETTER_DESC,
                null,
                null
            );
            stackStaticComponent(getter, containerImplName, fieldName, fieldDescriptor);
            getter.visitInsn(Opcodes.ARETURN);
            getter.visitEnd();
        }
        init.visitInsn(Opcodes.RETURN);
        init.visitEnd();
        get.visitVarInsn(Opcodes.ALOAD, 0);
        get.visitVarInsn(Opcodes.ALOAD, 1);
        get.visitMethodInsn(Opcodes.INVOKESPECIAL, CcaAsmHelper.DYNAMIC_COMPONENT_CONTAINER_IMPL, "get", GET_DESC, false);
        get.visitInsn(Opcodes.ARETURN);
        get.visitEnd();
        forEach.visitVarInsn(Opcodes.ALOAD, 0);
        forEach.visitVarInsn(Opcodes.ALOAD, 1);
        forEach.visitMethodInsn(Opcodes.INVOKESPECIAL, CcaAsmHelper.DYNAMIC_COMPONENT_CONTAINER_IMPL, "forEach", "(L" + Type.getInternalName(BiConsumer.class) + ";)V", false);
        forEach.visitInsn(Opcodes.RETURN);
        forEach.visitEnd();
        canBeAssigned.visitVarInsn(Opcodes.ALOAD, 0);
        canBeAssigned.visitVarInsn(Opcodes.ALOAD, 1);
        canBeAssigned.visitMethodInsn(Opcodes.INVOKESPECIAL, CcaAsmHelper.DYNAMIC_COMPONENT_CONTAINER_IMPL, "canBeAssigned", CAN_BE_ASSIGNED_DESC, false);
        canBeAssigned.visitJumpInsn(Opcodes.IFEQ, canBeAssignedFalse);
        Label canBeAssignedEnd = new Label();
        canBeAssigned.visitInsn(Opcodes.ICONST_1);
        canBeAssigned.visitJumpInsn(Opcodes.GOTO, canBeAssignedEnd);
        canBeAssigned.visitLabel(canBeAssignedFalse);
        canBeAssigned.visitInsn(Opcodes.ICONST_0);
        canBeAssigned.visitLabel(canBeAssignedEnd);
        canBeAssigned.visitInsn(Opcodes.IRETURN);
        canBeAssigned.visitEnd();
        @SuppressWarnings("unchecked") Class<? extends ComponentContainer<?>> ret = (Class<? extends ComponentContainer<?>>) CcaAsmHelper.generateClass(classNode);
        for (Map.Entry<Identifier, I> entry : componentFactories.entrySet()) {
            try {
                Field factoryField = ret.getDeclaredField(getFactoryFieldName(entry.getKey()));
                factoryField.setAccessible(true);
                factoryField.set(null, entry.getValue());
            } catch (NoSuchFieldException | IllegalAccessException e) {
                throw new StaticComponentLoadingException("Failed to initialize factory field for component type " + entry.getKey(), e);
            }
        }
        return ret;
    }

    @NotNull
    private static String getFactoryFieldName(Identifier identifier) {
        return CcaAsmHelper.getJavaIdentifierName(identifier) + "$factory";
    }

    /**
     * Defines an implementation of {@code I} which creates component containers of
     * the given implementation type, using an argument of the given {@code factoryArg} type.
     *
     * <p>The generated class has a single constructor, taking {@code eventCount} parameters of type {@link Event}.
     *
     * @param implNameSuffix a unique suffix for the generated class
     * @param containerFactoryType the factory interface that is to be implemented by the returned class
     * @param containerImpl  the type of containers that is to be instantiated by the generated factory
     * @param actualFactoryParams the actual type of the arguments taken by the {@link ComponentContainer} constructor
     */
    public static <I> Class<? extends I> spinContainerFactory(String implNameSuffix, Class<? super I> containerFactoryType, Class<? extends ComponentContainer<?>> containerImpl, @Nullable Class<?> componentCallbackType, int eventCount, Class<?>... actualFactoryParams) throws IOException {
        checkValidJavaIdentifier(implNameSuffix);
        Constructor<?>[] constructors = containerImpl.getConstructors();

        if (constructors.length != 1) {
            throw new IllegalStateException("Ambiguous constructor declarations in " + containerImpl + ": " + Arrays.toString(constructors));
        }

        Method factorySam = CcaAsmHelper.findSam(containerFactoryType);
        Method componentCallbackSam;
        String componentCallbackDesc;

        if (factorySam.getParameterCount() != actualFactoryParams.length) {
            throw new IllegalArgumentException("Actual argument list length mismatches with factory SAM: " + Arrays.toString(actualFactoryParams) + " and " + factorySam);
        }

        if (componentCallbackType != null) {
            componentCallbackSam = CcaAsmHelper.findSam(componentCallbackType);

            if (componentCallbackSam.getReturnType() != void.class) {
                throw new IllegalArgumentException("A component callback method must have a void return type, got " + componentCallbackSam);
            }

            componentCallbackDesc = Type.getMethodDescriptor(componentCallbackSam);

            // callbacks have one more argument, for container instance
            if (componentCallbackSam.getParameterCount() != factorySam.getParameterCount() + 1) {
                throw new IllegalArgumentException("Component callback argument list length mismatches with factory SAM: " + componentCallbackSam + " and " + factorySam);
            }
        } else {
            componentCallbackSam = null;
            componentCallbackDesc = null;
        }

        // constructor has one more argument, for expected size
        if (constructors[0].getParameterCount() != factorySam.getParameterCount() + 1) {
            throw new IllegalArgumentException("Factory SAM parameter count should be one less than container constructor (found " + factorySam + " for " + constructors[0] + ")");
        }
        Type[] factoryArgs;
        Type[] callbackArgs;
        {
            Class<?>[] factoryParamClasses = factorySam.getParameterTypes();
            Class<?>[] callbackParamClasses = componentCallbackSam == null ? null : componentCallbackSam.getParameterTypes();
            factoryArgs = new Type[factoryParamClasses.length];
            callbackArgs = new Type[factoryParamClasses.length];
            for (int i = 0; i < factoryParamClasses.length; i++) {
                if (!factoryParamClasses[i].isAssignableFrom(actualFactoryParams[i])) {
                    throw new IllegalArgumentException("Container factory parameter " + factoryParamClasses[i].getSimpleName() + " is not assignable from specified actual parameter " + actualFactoryParams[i].getSimpleName() + "(" + factorySam + ", " + Arrays.toString(actualFactoryParams) + ")");
                } else if (callbackParamClasses != null && !callbackParamClasses[i].isAssignableFrom(actualFactoryParams[i])) {
                    throw new IllegalArgumentException("Component callback parameter " + callbackParamClasses[i].getSimpleName() + " is not assignable from specified actual parameter " + actualFactoryParams[i].getSimpleName() + "(" + factorySam + ", " + Arrays.toString(actualFactoryParams) + ")");
                }
                factoryArgs[i] = Type.getType(factoryParamClasses[i]);
                if (callbackParamClasses != null) {
                    callbackArgs[i] = Type.getType(callbackParamClasses[i]);
                }
            }

            if (callbackParamClasses != null && callbackParamClasses[callbackParamClasses.length - 1] != ComponentContainer.class) {
                throw new IllegalArgumentException("A component callback method must have a " + ComponentContainer.class + " as its last parameter, got " + componentCallbackSam);
            }
        }
        String containerCtorDesc = Type.getConstructorDescriptor(constructors[0]);
        String containerImplName = Type.getInternalName(containerImpl);
        ClassNode containerFactoryWriter = new ClassNode(CcaAsmHelper.ASM_VERSION);
        String factoryImplName = CcaAsmHelper.STATIC_CONTAINER_FACTORY + '_' + implNameSuffix;
        containerFactoryWriter.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, factoryImplName, null, "java/lang/Object", new String[] {Type.getInternalName(containerFactoryType)});
        String componentEventsFieldDesc = EVENT_DESC;
        String componentEventsField = "componentEvent";
        String componentCallbackName = componentCallbackType == null ? null : Type.getInternalName(componentCallbackType);
        StringBuilder ctorDesc = new StringBuilder("(");
        for (int i = 0; i < eventCount; i++) {
            containerFactoryWriter.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, componentEventsField + "_" + i, componentEventsFieldDesc, null, null);
            ctorDesc.append(componentEventsFieldDesc);
        }
        ctorDesc.append(")V");
        containerFactoryWriter.visitField(Opcodes.ACC_PRIVATE, "expectedSize", "I", null, 0);
        MethodVisitor init = containerFactoryWriter.visitMethod(Opcodes.ACC_PUBLIC, "<init>", ctorDesc.toString(), null, null);
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        for (int i = 0; i < eventCount; i++) {
            init.visitVarInsn(Opcodes.ALOAD, 0);
            init.visitVarInsn(Opcodes.ALOAD, i + 1);
            init.visitFieldInsn(Opcodes.PUTFIELD, factoryImplName, componentEventsField + "_" + i, componentEventsFieldDesc);
        }
        init.visitInsn(Opcodes.RETURN);
        init.visitEnd();
        MethodVisitor createContainer = containerFactoryWriter.visitMethod(Opcodes.ACC_PUBLIC, factorySam.getName(), Type.getMethodDescriptor(factorySam), null, null);
        createContainer.visitTypeInsn(Opcodes.NEW, containerImplName);
        createContainer.visitInsn(Opcodes.DUP);
        createContainer.visitVarInsn(Opcodes.ALOAD, 0);
        // stack: <this>
        createContainer.visitFieldInsn(Opcodes.GETFIELD, factoryImplName, "expectedSize", "I");
        // stack: this.expectedSize
        for (int i2 = 0; i2 < actualFactoryParams.length; i2++) {
            createContainer.visitVarInsn(factoryArgs[i2].getOpcode(Opcodes.ILOAD), i2 + 1);
            if (factoryArgs[i2].getSort() == Type.OBJECT || factoryArgs[i2].getSort() == Type.ARRAY) {
                createContainer.visitTypeInsn(Opcodes.CHECKCAST, Type.getInternalName(actualFactoryParams[i2]));
            }
        }
        // stack: this.expectedSize actualFactoryArgs...
        createContainer.visitMethodInsn(Opcodes.INVOKESPECIAL, containerImplName, "<init>", containerCtorDesc, false);
        // stack: container
        if (componentCallbackType != null) {
            for (int i = 0; i < eventCount; i++) {
                createContainer.visitInsn(Opcodes.DUP);
                // stack: container container
                createContainer.visitVarInsn(Opcodes.ALOAD, 0);
                // stack: container container <this>
                createContainer.visitFieldInsn(Opcodes.GETFIELD, factoryImplName, componentEventsField + "_" + i, componentEventsFieldDesc);
                // stack: container container this.componentEvent_i
                createContainer.visitMethodInsn(Opcodes.INVOKEVIRTUAL, EVENT_NAME, "invoker", EVENT$INVOKER_DESC, false);
                // stack: container container componentCallback_i
                createContainer.visitTypeInsn(Opcodes.CHECKCAST, componentCallbackName);
                createContainer.visitInsn(Opcodes.SWAP);
                // stack: container componentCallback_i container
                for (int j = 0; j < actualFactoryParams.length; j++) {
                    createContainer.visitVarInsn(callbackArgs[j].getOpcode(Opcodes.ILOAD), j + 1);
                    if (callbackArgs[j].getSort() == Type.OBJECT || callbackArgs[j].getSort() == Type.ARRAY) {
                        createContainer.visitTypeInsn(Opcodes.CHECKCAST, Type.getInternalName(actualFactoryParams[j]));
                    }
                    createContainer.visitInsn(Opcodes.SWAP);
                }
                // stack: container componentCallback_i callbackArgs... container
                createContainer.visitMethodInsn(Opcodes.INVOKEINTERFACE, componentCallbackName, componentCallbackSam.getName(), componentCallbackDesc, true);
                // stack: container
            }
        }
        createContainer.visitInsn(Opcodes.ARETURN);
        createContainer.visitEnd();
        containerFactoryWriter.visitEnd();
        @SuppressWarnings("unchecked") Class<? extends I> ret = (Class<? extends I>) CcaAsmHelper.generateClass(containerFactoryWriter);
        return ret;
    }

    private static void checkValidJavaIdentifier(String implNameSuffix) {
        for (int i = 0; i < implNameSuffix.length(); i++) {
            if (!Character.isJavaIdentifierPart(implNameSuffix.charAt(i))) {
                throw new IllegalArgumentException(implNameSuffix + " is not a valid suffix for a java identifier");
            }
        }
    }

    private static void stackStaticComponent(MethodVisitor method, String containerImplName, String fieldName, String fieldDescriptor) {
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitFieldInsn(Opcodes.GETFIELD, containerImplName, fieldName, fieldDescriptor);
    }

    private static void stackStaticComponentType(MethodVisitor method, Identifier componentId) {
        // get the generated lazy component type constant
        method.visitFieldInsn(Opcodes.GETSTATIC, CcaAsmHelper.STATIC_COMPONENT_TYPES, CcaAsmHelper.getTypeConstantName(componentId), "L" + CcaAsmHelper.LAZY_COMPONENT_TYPE + ";");
        // stack: <this> component lazyComponentType
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, CcaAsmHelper.LAZY_COMPONENT_TYPE, "get", "()L" + CcaAsmHelper.COMPONENT_TYPE + ";", false);
    }

    public Class<? extends DynamicContainerFactory<T,?>> getContainerFactoryClass() {
        this.ensureInitialized();

        return this.containerFactoryClass;
    }

    @Override
    protected void init() {
        CcaBootstrap.INSTANCE.processSpecializedInitializers(this.initializerType, (entrypoint, provider) -> this.dispatchRegistration(entrypoint));

        try {
            Class<? extends ComponentContainer<?>> containerCls = spinComponentContainer(this.componentFactoryType, this.componentFactories, this.implSuffix);
            this.containerFactoryClass = spinContainerFactory(this.implSuffix, DynamicContainerFactory.class, containerCls, ComponentCallback.class, 1, this.providerClass);
        } catch (IOException e) {
            throw new StaticComponentLoadingException("Failed to generate a dedicated component container for " + this.providerClass, e);
        }
    }

    protected abstract void dispatchRegistration(I entrypoint);

    protected void register(Identifier componentId, F factory) {
        this.componentFactories.put(componentId, factory);
    }
}

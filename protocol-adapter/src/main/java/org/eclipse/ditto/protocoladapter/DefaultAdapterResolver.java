/*
 * Copyright (c) 2020 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.ditto.protocoladapter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.eclipse.ditto.protocoladapter.provider.AcknowledgementAdapterProvider;
import org.eclipse.ditto.protocoladapter.provider.PolicyCommandAdapterProvider;
import org.eclipse.ditto.protocoladapter.provider.ThingCommandAdapterProvider;
import org.eclipse.ditto.signals.base.Signal;

/**
 * Implements the logic to select the correct {@link Adapter} from a given {@link Adaptable}.
 */
final class DefaultAdapterResolver implements AdapterResolver {

    private final Function<Adaptable, Adapter<?>> resolver;

    public DefaultAdapterResolver(final ThingCommandAdapterProvider thingsAdapters,
            final PolicyCommandAdapterProvider policiesAdapters,
            final AcknowledgementAdapterProvider acknowledgementAdapters) {
        final List<Adapter<?>> adapters = new ArrayList<>();
        adapters.addAll(thingsAdapters.getAdapters());
        adapters.addAll(policiesAdapters.getAdapters());
        adapters.addAll(acknowledgementAdapters.getAdapters());
        resolver = computeResolver(adapters);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Adapter<? extends Signal<?>> getAdapter(final Adaptable adaptable) {
        return (Adapter<? extends Signal<?>>) resolver.apply(adaptable);
    }

    private static boolean isResponse(final Adaptable adaptable) {
        return adaptable.getPayload().getStatus().isPresent();
    }

    private static <T> List<T> filter(final List<T> list, final Predicate<T> predicate) {
        return list.stream().filter(predicate).collect(Collectors.toList());
    }

    private static <T> T throwUnknownTopicPathException(final Adaptable adaptable) {
        throw UnknownTopicPathException.newBuilder(adaptable.getTopicPath()).build();
    }

    private static <T> T throwAmbiguityDetectedException(final List<Adapter<?>> adapters) {
        // Ambiguity detected: Adapters have overlapping topic paths.
        throw new IllegalStateException("Indistinguishable adapters detected: " + adapters);
    }

    private static <S, T> Function<S, T> constantFunction(final T result) {
        return ignored -> result;
    }

    /**
     * Create an adapter resolution function according to whether an input adaptable is a response
     * and whether it requires a subject.
     * It is the final stage of adapter resolution.
     *
     * @param adapters relevant adapters at this final stage.
     * @return the adapter resolution function.
     */
    private static Function<Adaptable, Adapter<?>> finalStep(final List<Adapter<?>> adapters) {
        return forEnum(adapters, Bool.class, Bool.values(),
                Bool.composeAsSet(Adapter::isForResponses),
                Bool.compose(DefaultAdapterResolver::isResponse),
                requiresSubjectStep -> forEnum(requiresSubjectStep, Bool.class, Bool.values(),
                        Bool.composeAsSet(Adapter::requiresSubject),
                        Bool.compose(adaptable -> adaptable.getTopicPath().getSubject().isPresent()),
                        DefaultAdapterResolver::throwAmbiguityDetectedException
                ));
    }

    /**
     * Create an EnumMap of subroutines for dispatching according to an enum value during adapter resolution.
     *
     * @param adapters the list of relevant adapters.
     * @param enumClass the class of the enum type.
     * @param enumValues all values of the enum type.
     * @param enumExtractor extractor for the set of supported enum values from an adapter.
     * @param nextStage the factory of subroutines in the enum map.
     * @param <T> the enum type.
     * @return the enum map of subroutines.
     */
    private static <T extends Enum<T>> EnumMapOrFunction<T> dispatchByEnum(
            final List<Adapter<?>> adapters,
            final Class<T> enumClass,
            final T[] enumValues,
            final Function<Adapter<?>, Set<T>> enumExtractor,
            final Function<List<Adapter<?>>, Function<Adaptable, Adapter<?>>> nextStage) {

        final Map<T, List<Adapter<?>>> matchingAdaptersMap = Arrays.stream(enumValues)
                .collect(Collectors.toMap(
                        Function.identity(),
                        enumValue -> filter(adapters, adapter -> enumExtractor.apply(adapter).contains(enumValue))
                ));

        final Optional<List<Adapter<?>>> matchingAdaptersMapHaveIdenticalValues = matchingAdaptersMap.values()
                .stream()
                .map(Optional::of)
                .reduce((list1, list2) -> list1.equals(list2) ? list1 : Optional.empty())
                .flatMap(Function.identity());

        if (matchingAdaptersMapHaveIdenticalValues.isPresent()) {
            return new IsFunction<>(selectMatchedAdapters(matchingAdaptersMapHaveIdenticalValues.get(), nextStage));
        } else {
            final EnumMap<T, Function<Adaptable, Adapter<?>>> enumMap = new EnumMap<>(enumClass);
            matchingAdaptersMap.forEach((enumValue, matchingAdapters) ->
                    enumMap.put(enumValue, selectMatchedAdapters(matchingAdapters, nextStage))
            );
            return new IsEnumMap<>(enumMap);
        }
    }

    private static Function<Adaptable, Adapter<?>> selectMatchedAdapters(
            final List<Adapter<?>> matchingAdapters,
            final Function<List<Adapter<?>>, Function<Adaptable, Adapter<?>>> nextStage) {

        if (matchingAdapters.isEmpty()) {
            return DefaultAdapterResolver::throwUnknownTopicPathException;
        } else if (matchingAdapters.size() == 1) {
            return constantFunction(matchingAdapters.get(0));
        } else {
            return nextStage.apply(matchingAdapters);
        }
    }

    /**
     * Similar to this#evalEnumMap, but for cases where not all instances of the argument type has a valid enum value.
     *
     * @param enumMap the enum map to evaluate.
     * @param emptyResult the subroutine to evaluate when the argument has no valid enum value.
     * @param optionalEnumExtractor extractor of an optional enum value from arguments.
     * @param <R> the type of arguments.
     * @param <S> the enum type.
     * @param <T> the type of results.
     * @return the function.
     */
    private static <R, S extends Enum<S>, T> Function<R, T> evalEnumMapByOptional(
            final EnumMap<S, Function<R, T>> enumMap,
            final Function<R, T> emptyResult,
            final Function<R, Optional<S>> optionalEnumExtractor) {
        return r -> {
            final Optional<S> optionalEnumValue = optionalEnumExtractor.apply(r);
            if (optionalEnumValue.isPresent()) {
                return enumMap.get(optionalEnumValue.get()).apply(r);
            } else {
                return emptyResult.apply(r);
            }
        };
    }

    /**
     * Create a fast function by evaluating a subroutine stored in an enum map according to the enum value of each
     * function argument.
     *
     * @param enumMap the enum map to evaluate.
     * @param enumExtractor the function to extract an enum value from the argument.
     * @param <R> the type of arguments.
     * @param <S> the enum type.
     * @param <T> the type of results.
     * @return the function.
     */
    private static <R, S extends Enum<S>, T> Function<R, T> evalEnumMap(
            final EnumMap<S, Function<R, T>> enumMap,
            final Function<R, S> enumExtractor) {
        return r -> enumMap.get(enumExtractor.apply(r)).apply(r);
    }

    // TODO: convert these "evalByOptional" steps to actual step objects
    private static Function<Adaptable, Adapter<?>> actionStep(final List<Adapter<?>> adapters) {
        final EnumMapOrFunction<TopicPath.Action> dispatchByAction =
                dispatchByEnum(adapters, TopicPath.Action.class, TopicPath.Action.values(),
                        Adapter::getActions, DefaultAdapterResolver::finalStep);
        // consider adapters that support no action to be those that support adaptables without action,
        // e. g., message commands and responses
        final List<Adapter<?>> noActionAdapters = filter(adapters, adapter -> adapter.getActions().isEmpty());
        return dispatchByAction.evalByOptional(searchActionStep(noActionAdapters), forTopicPath(TopicPath::getAction));
    }

    private static Function<Adaptable, Adapter<?>> searchActionStep(final List<Adapter<?>> adapters) {
        final EnumMapOrFunction<TopicPath.SearchAction> dispatchBySearchAction =
                dispatchByEnum(adapters, TopicPath.SearchAction.class, TopicPath.SearchAction.values(),
                        Adapter::getSearchActions, DefaultAdapterResolver::finalStep);
        // consider adapters that support no search action to be those that support adaptables without search action,
        // e. g.,  all non-search signals
        final List<Adapter<?>> noSearchActionAdapters =
                filter(adapters, adapter -> adapter.getSearchActions().isEmpty());
        return dispatchBySearchAction.evalByOptional(finalStep(noSearchActionAdapters),
                forTopicPath(TopicPath::getSearchAction));
    }

    /**
     * Compute the adapter resolver function for current and subsequent enum dimensions.
     *
     * @param adapters the list of relevant adapters at this step.
     * @param enumClass the class of the enum type to dispatch at this step.
     * @param enumValues all values of the enum class.
     * @param getSupportedEnums the function to extract a set of supported enum values from an adapter.
     * @param extractEnum the function to extract the enum value from an adaptable for adapter resolution.
     * @param nextStep the factory for the adapter resolver function for subsequent enum dimensions.
     * @param <T> the enum type.
     * @return the adapter resolver function.
     */
    private static <T extends Enum<T>> Function<Adaptable, Adapter<?>> forEnum(
            final List<Adapter<?>> adapters,
            final Class<T> enumClass,
            final T[] enumValues,
            final Function<Adapter<?>, Set<T>> getSupportedEnums,
            final Function<Adaptable, T> extractEnum,
            final Function<List<Adapter<?>>, Function<Adaptable, Adapter<?>>> nextStep) {
        return dispatchByEnum(adapters, enumClass, enumValues, getSupportedEnums, nextStep).eval(extractEnum);
    }

    /**
     * Convert an extracting function for TopicPath into one for Adaptable.
     *
     * @param extractor the extracting function for TopicPath.
     * @return the extracting function for Adaptable.
     */
    private static <T> Function<Adaptable, T> forTopicPath(final Function<TopicPath, T> extractor) {
        return extractor.compose(Adaptable::getTopicPath);
    }

    /**
     * Recursive function to compute the adapter resolver function.
     *
     * @param adapters list of all known adapters.
     * @param finalStep the final step after exhausting {@code resolverSteps}.
     * @param i the index of the current resolver step.
     * @param resolverSteps the list of resolver steps, each holding enough information to compute a function that
     * restricts potential adapters according to 1 enum attribute of an adaptable.
     * @return the adapter resolver function.
     */
    private static Function<Adaptable, Adapter<?>> computeResolverRecursively(final List<Adapter<?>> adapters,
            final Function<List<Adapter<?>>, Function<Adaptable, Adapter<?>>> finalStep,
            final int i,
            final List<ResolverStep<?>> resolverSteps) {
        if (i >= resolverSteps.size()) {
            return finalStep.apply(adapters);
        } else {
            final int j = i + 1;
            return resolverSteps.get(i).combine(adapters,
                    nextAdapters -> computeResolverRecursively(nextAdapters, finalStep, j, resolverSteps));
        }
    }

    /**
     * Compute a fast adapter resolution function from a list of known adapters.
     *
     * @param adapters all known adapters.
     * @return a function to find an adapter for an adaptable quickly.
     */
    private static Function<Adaptable, Adapter<?>> computeResolver(final List<Adapter<?>> adapters) {
        return computeResolverRecursively(adapters, DefaultAdapterResolver::actionStep, 0, Arrays.asList(
                new ResolverStep<>(TopicPath.Group.class, TopicPath.Group.values(), Adapter::getGroups,
                        forTopicPath(TopicPath::getGroup)),
                new ResolverStep<>(TopicPath.Channel.class, TopicPath.Channel.values(), Adapter::getChannels,
                        forTopicPath(TopicPath::getChannel)),
                new ResolverStep<>(TopicPath.Criterion.class, TopicPath.Criterion.values(), Adapter::getCriteria,
                        forTopicPath(TopicPath::getCriterion))
        ));
    }

    /**
     * Describe 1 resolver step that dispatches an Adaptable according to 1 attribute of Enum type.
     *
     * @param <T> the type of the distinguishing attribute. Must be an Enum for performance.
     */
    private static final class ResolverStep<T extends Enum<T>> {

        final Class<T> enumClass;
        final T[] enumValues;
        final Function<Adapter<?>, Set<T>> getSupportedEnums;
        final Function<Adaptable, T> extractEnum;

        /**
         * Construct a resolver step.
         *
         * @param enumClass the Enum class.
         * @param enumValues all values of the Enum class.
         * @param getSupportedEnums extract the set of supported enum values from an adapter.
         * @param extractEnum extract the enum value from an adaptable to restrict possible adapters.
         */
        private ResolverStep(final Class<T> enumClass,
                final T[] enumValues,
                final Function<Adapter<?>, Set<T>> getSupportedEnums,
                final Function<Adaptable, T> extractEnum) {
            this.enumClass = enumClass;
            this.enumValues = enumValues;
            this.getSupportedEnums = getSupportedEnums;
            this.extractEnum = extractEnum;
        }

        private Function<Adaptable, Adapter<?>> combine(
                final List<Adapter<?>> currentAdapters,
                final Function<List<Adapter<?>>, Function<Adaptable, Adapter<?>>> nextStep) {
            return forEnum(currentAdapters, enumClass, enumValues, getSupportedEnums, extractEnum, nextStep);
        }
    }

    private interface EnumMapOrFunction<T> {

        Function<Adaptable, Adapter<?>> eval(Function<Adaptable, T> extractor);

        Function<Adaptable, Adapter<?>> evalByOptional(
                Function<Adaptable, Adapter<?>> emptyResult,
                Function<Adaptable, Optional<T>> optionalEnumExtractor);
    }

    private static final class IsEnumMap<T extends Enum<T>> implements EnumMapOrFunction<T> {

        private final EnumMap<T, Function<Adaptable, Adapter<?>>> enumMap;

        private IsEnumMap(final EnumMap<T, Function<Adaptable, Adapter<?>>> enumMap) {
            this.enumMap = enumMap;
        }

        @Override
        public Function<Adaptable, Adapter<?>> eval(final Function<Adaptable, T> extractor) {
            return evalEnumMap(enumMap, extractor);
        }

        @Override
        public Function<Adaptable, Adapter<?>> evalByOptional(
                final Function<Adaptable, Adapter<?>> emptyResult,
                final Function<Adaptable, Optional<T>> optionalEnumExtractor) {

            return evalEnumMapByOptional(enumMap, emptyResult, optionalEnumExtractor);
        }
    }

    private static final class IsFunction<T> implements EnumMapOrFunction<T> {

        private final Function<Adaptable, Adapter<?>> function;

        private IsFunction(final Function<Adaptable, Adapter<?>> function) {
            this.function = function;
        }

        @Override
        public Function<Adaptable, Adapter<?>> eval(final Function<Adaptable, T> extractor) {
            return function;
        }

        @Override
        public Function<Adaptable, Adapter<?>> evalByOptional(final Function<Adaptable, Adapter<?>> emptyResult,
                final Function<Adaptable, Optional<T>> optionalEnumExtractor) {
            return r -> optionalEnumExtractor.apply(r).isPresent() ? function.apply(r) : emptyResult.apply(r);
        }
    }

    private enum Bool {
        TRUE,
        FALSE;

        private static Bool of(final boolean bool) {
            return bool ? TRUE : FALSE;
        }

        private static <T> Function<T, Bool> compose(final Predicate<T> predicate) {
            return t -> Bool.of(predicate.test(t));
        }

        private static <T> Function<T, Set<Bool>> composeAsSet(final Predicate<T> predicate) {
            return t -> EnumSet.of(Bool.of(predicate.test(t)));
        }
    }
}

/**
 * Copyright (c) Codice Foundation
 *
 * <p>This is free software: you can redistribute it and/or modify it under the terms of the GNU
 * Lesser General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or any later version.
 *
 * <p>This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details. A copy of the GNU Lesser General Public
 * License is distributed along with this program and can be found at
 * <http://www.gnu.org/licenses/lgpl.html>.
 */
package ddf.util;

import static ddf.util.Fallible.error;
import static ddf.util.Fallible.of;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collector;
import java.util.stream.Collectors;

public class MapUtils {

  /**
   * Applies two given functions to each of the keys and values of a given map, respectively.
   *
   * @param oldMap the {@link Map} to which the two functions will be applied.
   * @param keyTransformer the function to be applied to each key in the {@link Map}.
   * @param valueTransformer the function to be applied to each value in the {@link Map}.
   * @param <OldKey> the type of the key in the given old {@link Map}.
   * @param <OldValue> the type of the value in the given old {@link Map}.
   * @param <NewKey> the type of the key that the old {@link Map}'s keys will be transformed into.
   * @param <NewValue> the type of the value that the old {@link Map}'s values will be transformed
   *     into.
   * @return a new {@link Map} made by applying the two given transformer functions to the given
   *     {@link Map}.
   */
  public static <OldKey, OldValue, NewKey, NewValue> Map<NewKey, NewValue> map(
      Map<OldKey, OldValue> oldMap,
      Function<OldKey, NewKey> keyTransformer,
      Function<OldValue, NewValue> valueTransformer) {
    return oldMap
        .entrySet()
        .stream()
        .collect(
            Collectors.toMap(
                entry -> keyTransformer.apply(entry.getKey()),
                entry -> valueTransformer.apply(entry.getValue())));
  }

  /**
   * Applies the given function to the keys in the given {@link Map}.
   *
   * @param oldMap the {@link Map} to be transformed.
   * @param keyTransformer the function to be applied to each key in the {@link Map}.
   * @param <OldKey> the type of the key in the given old {@link Map}.
   * @param <Value> the type of the values in the {@link Map} which will not be modified.
   * @param <NewKey> the type of the key that the old {@link Map}'s keys will be transformed into.
   * @return a new {@link Map} with keys resulting from transforming the keys in the given {@link
   *     Map} and the same values as given in the original {@link Map}.
   */
  public static <OldKey, Value, NewKey> Map<NewKey, Value> mapKeys(
      Map<OldKey, Value> oldMap, Function<OldKey, NewKey> keyTransformer) {
    return map(oldMap, keyTransformer, Function.identity());
  }

  /**
   * Applies the given function to the values in the given {@link Map}.
   *
   * @param oldMap the {@link Map} to be transformed.
   * @param valueTransformer the function to be applied to each value in the {@link Map}.
   * @param <Key> the type of the keys in the {@link Map} which will not be modified.
   * @param <OldValue> the type of the value in the given old {@link Map}.
   * @param <NewValue> the type of the value that the old {@link Map}'s values will be transformed
   *     into.
   * @return a new {@link Map} with values resulting from transforming the values in the given
   *     {@link Map} and the same keys as given in the original {@link Map}.
   */
  public static <Key, OldValue, NewValue> Map<Key, NewValue> mapValues(
      Map<Key, OldValue> oldMap, Function<OldValue, NewValue> valueTransformer) {
    return map(oldMap, Function.identity(), valueTransformer);
  }

  /**
   * Creates a {@link Map} from a {@link java.util.List} using a function given to generate the map
   * keys from each element. If keys generated from two different elements in the list result in the
   * same key, an {@link IllegalStateException} is thrown as per the implementation of {@link
   * Collectors#toMap(Function, Function)}.
   *
   * @param values the {@link java.util.List} of values which will become the resulting map's
   *     values.
   * @param keyMaker the function that can generate a key from each element in the given list of
   *     values.
   * @param <Key> the type of the keys to be generated from the given values.
   * @param <Value> the type of the values given
   * @return a new {@link Map} where the keys are generated from the given function applied to
   *     corresponding values and the values are taken from the given list of values.
   */
  public static <Key, Value> Map<Key, Value> key(
      Collection<Value> values, Function<Value, Key> keyMaker) {
    return values.stream().collect(Collectors.toMap(keyMaker, Function.identity()));
  }

  /**
   * A {@link Collector} to transform a {@link java.util.stream.Stream} of {@link Map.Entry
   * Map.Entries} into a {@link Map}. If two keys in the given {@link java.util.List} have the same
   * key, an {@link IllegalStateException} is thrown as per the implementation of {@link
   * Collectors#toMap(Function, Function)}.
   *
   * @param <Key> the type of the keys in the given {@link Map.Entry Map.Entries} and the resulting
   *     {@link Map}.
   * @param <Value> the type of the values in the given {@link Map.Entry Map.Entries} and the
   *     resulting {@link Map}.
   * @return a new {@link Map} where the keys and values are taken from the {@link Map.Entry
   *     Map.Entries} given.
   */
  public static <Key, Value>
      Collector<? extends Map.Entry<Key, Value>, ?, ? extends Map<Key, Value>> collectEntries() {
    return Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue);
  }

  public static <Element, Key, Value> Map<Key, Value> fromList(
      List<Element> list, Function<Element, Key> keyMaker, Function<Element, Value> valueMaker) {
    return list.stream().collect(Collectors.toMap(keyMaker, valueMaker));
  }

  public static <Key, MapValue, Casted extends MapValue> Fallible<Casted> tryGet(
      Map<? super Key, MapValue> map, Key key, Class<Casted> expectedClass) {
    final MapValue mapValue = map.get(key);
    if (mapValue == null) {
      return error("This map has no key \"%s\"!", key);
    } else if (!expectedClass.isInstance(mapValue)) {
      return error(
          "This map's value corresponding to \"%s\" is not an instance of %s!",
          key, expectedClass.getName());
    } else {
      return of(expectedClass.cast(mapValue));
    }
  }

  public static <Key, MapValue, Casted extends MapValue, Result> Fallible<Result> tryGetAndRun(
      Map<? super Key, MapValue> map,
      Key key,
      Class<Casted> expectedClass,
      Function<Casted, Fallible<Result>> function) {
    return tryGet(map, key, expectedClass).tryMap(function);
  }

  public static <Key, MapValue, Casted1 extends MapValue, Casted2 extends MapValue, Result>
      Fallible<Result> tryGetAndRun(
          Map<? super Key, MapValue> map,
          Key key1,
          Class<Casted1> expectedClass1,
          Key key2,
          Class<Casted2> expectedClass2,
          BiFunction<Casted1, Casted2, Fallible<Result>> function) {
    return tryGet(map, key1, expectedClass1)
        .tryMap(
            value1 ->
                tryGetAndRun(map, key2, expectedClass2, value2 -> function.apply(value1, value2)));
  }

  @FunctionalInterface
  public interface TriFunction<A, B, C, R> {

    R apply(A a, B b, C c);
  }

  public static <
          Key,
          MapValue,
          Casted1 extends MapValue,
          Casted2 extends MapValue,
          Casted3 extends MapValue,
          Result>
      Fallible<Result> tryGetAndRun(
          Map<? super Key, MapValue> map,
          Key key1,
          Class<Casted1> expectedClass1,
          Key key2,
          Class<Casted2> expectedClass2,
          Key key3,
          Class<Casted3> expectedClass3,
          TriFunction<Casted1, Casted2, Casted3, Fallible<Result>> function) {
    return tryGetAndRun(
        map,
        key1,
        expectedClass1,
        key2,
        expectedClass2,
        (value1, value2) ->
            tryGet(map, key3, expectedClass3)
                .tryMap(value3 -> function.apply(value1, value2, value3)));
  }

  @FunctionalInterface
  public interface QuadFunction<A, B, C, D, R> {

    R apply(A a, B b, C c, D d);
  }

  public static <
          Key,
          MapValue,
          Casted1 extends MapValue,
          Casted2 extends MapValue,
          Casted3 extends MapValue,
          Casted4 extends MapValue,
          Result>
      Fallible<Result> tryGetAndRun(
          Map<? super Key, MapValue> map,
          Key key1,
          Class<Casted1> expectedClass1,
          Key key2,
          Class<Casted2> expectedClass2,
          Key key3,
          Class<Casted3> expectedClass3,
          Key key4,
          Class<Casted4> expectedClass4,
          QuadFunction<Casted1, Casted2, Casted3, Casted4, Fallible<Result>> function) {
    return tryGetAndRun(
        map,
        key1,
        expectedClass1,
        key2,
        expectedClass2,
        key3,
        expectedClass3,
        (value1, value2, value3) ->
            tryGet(map, key4, expectedClass4)
                .tryMap(value4 -> function.apply(value1, value2, value3, value4)));
  }

  @FunctionalInterface
  public interface QuintFunction<A, B, C, D, E, R> {

    R apply(A a, B b, C c, D d, E e);
  }

  public static <
          Key,
          MapValue,
          Casted1 extends MapValue,
          Casted2 extends MapValue,
          Casted3 extends MapValue,
          Casted4 extends MapValue,
          Casted5 extends MapValue,
          Result>
      Fallible<Result> tryGetAndRun(
          Map<? super Key, MapValue> map,
          Key key1,
          Class<Casted1> expectedClass1,
          Key key2,
          Class<Casted2> expectedClass2,
          Key key3,
          Class<Casted3> expectedClass3,
          Key key4,
          Class<Casted4> expectedClass4,
          Key key5,
          Class<Casted5> expectedClass5,
          QuintFunction<Casted1, Casted2, Casted3, Casted4, Casted5, Fallible<Result>> function) {
    return tryGetAndRun(
        map,
        key1,
        expectedClass1,
        key2,
        expectedClass2,
        key3,
        expectedClass3,
        key4,
        expectedClass4,
        (value1, value2, value3, value4) ->
            tryGet(map, key5, expectedClass5)
                .tryMap(value5 -> function.apply(value1, value2, value3, value4, value5)));
  }

  @FunctionalInterface
  public interface SextFunction<A, B, C, D, E, F, R> {

    R apply(A a, B b, C c, D d, E e, F f);
  }

  public static <
          Key,
          MapValue,
          Casted1 extends MapValue,
          Casted2 extends MapValue,
          Casted3 extends MapValue,
          Casted4 extends MapValue,
          Casted5 extends MapValue,
          Casted6 extends MapValue,
          Result>
      Fallible<Result> tryGetAndRun(
          Map<? super Key, MapValue> map,
          Key key1,
          Class<Casted1> expectedClass1,
          Key key2,
          Class<Casted2> expectedClass2,
          Key key3,
          Class<Casted3> expectedClass3,
          Key key4,
          Class<Casted4> expectedClass4,
          Key key5,
          Class<Casted5> expectedClass5,
          Key key6,
          Class<Casted6> expectedClass6,
          SextFunction<Casted1, Casted2, Casted3, Casted4, Casted5, Casted6, Fallible<Result>>
              function) {
    return tryGetAndRun(
        map,
        key1,
        expectedClass1,
        key2,
        expectedClass2,
        key3,
        expectedClass3,
        key4,
        expectedClass4,
        key5,
        expectedClass5,
        (value1, value2, value3, value4, value5) ->
            tryGet(map, key6, expectedClass6)
                .tryMap(value6 -> function.apply(value1, value2, value3, value4, value5, value6)));
  }
}
// Copyright 2020 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package google.registry.model.translators;

import static com.google.common.base.Functions.identity;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static google.registry.model.EntityClasses.ALL_CLASSES;
import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;

import com.google.appengine.api.datastore.Key;
import com.google.common.collect.ImmutableMap;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.EntitySubclass;
import com.googlecode.objectify.impl.Property;
import google.registry.persistence.VKey;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

/**
 * Translator factory for VKey.
 *
 * <p>These get translated to a string containing the URL safe encoding of the objectify key
 * followed by a (url-unsafe) ampersand delimiter and the SQL key.
 */
public class VKeyTranslatorFactory extends AbstractSimpleTranslatorFactory<VKey, Key> {

  // Class registry allowing us to restore the original class object from the unqualified class
  // name, which is all the datastore key gives us.
  // Note that entities annotated with @EntitySubclass(and its parent abstract entities) are removed
  // because they share the same kind of the key with their parent class.
  private static final ImmutableMap<String, Class> CLASS_REGISTRY =
      ALL_CLASSES.stream()
          .filter(
              clazz ->
                  !clazz.isAnnotationPresent(Entity.class)
                      || !Modifier.isAbstract(clazz.getModifiers()))
          .filter(clazz -> !clazz.isAnnotationPresent(EntitySubclass.class))
          .collect(toImmutableMap(com.googlecode.objectify.Key::getKind, identity()));

  public VKeyTranslatorFactory() {
    super(VKey.class);
  }

  /** Create a VKey from a raw datastore key. */
  public static VKey<?> createVKey(Key datastoreKey, Property vKeyProperty) {
    return createVKey(com.googlecode.objectify.Key.create(datastoreKey), vKeyProperty);
  }

  /** Create a VKey from an objectify Key. */
  public static <T> VKey<T> createVKey(com.googlecode.objectify.Key<T> key, Property vKeyProperty) {
    if (key == null) {
      return null;
    }
    checkArgumentNotNull(vKeyProperty, "Must specify vKeyProperty");
    // Try to get the VKey type from CLASS_REGISTRY.
    Class<T> clazz = CLASS_REGISTRY.get(key.getKind());
    if (clazz == null) {
      // Try to get the VKey type from its declared property. Note that this approach is mainly
      // designed for entities annotated with @EntitySubclass, e.g. PollMessage.OneTime, because
      // multiple @EntitySubclass entities share the same key kind in Objectify. Also, we only
      // support the basic use case of VKey, i.e. VKey<Entity>, in this case because we only use
      // it in the production code.
      if (vKeyProperty.getType() instanceof ParameterizedType) {
        ParameterizedType vKeyType = (ParameterizedType) vKeyProperty.getType();
        Type[] actualTypeArguments = vKeyType.getActualTypeArguments();
        if (actualTypeArguments.length == 1 && actualTypeArguments[0] instanceof Class) {
          clazz = (Class<T>) actualTypeArguments[0];
        }
      }
    }
    checkArgumentNotNull(clazz, "Unknown Key type: %s", key.getKind());
    // Try to create the VKey from its reference type.
    try {
      Method createVKeyMethod =
          clazz.getDeclaredMethod("createVKey", com.googlecode.objectify.Key.class);
      return (VKey<T>) createVKeyMethod.invoke(null, new Object[] {key});
    } catch (NoSuchMethodException e) {
      // Revert to an ofy vkey for now.  TODO(mmuller): remove this when all classes with VKeys have
      // converters.
      return VKey.createOfy(clazz, key);
    } catch (IllegalAccessException | InvocationTargetException e) {
      // If we have a createVKey(Key) method with incorrect permissions or that is non-static, this
      // is probably an error so let's reported.
      throw new RuntimeException(e);
    }
  }

  /** Create a VKey from a URL-safe string representation. */
  public static VKey<?> createVKey(String urlSafe, Property vKeyProperty) {
    return createVKey(com.googlecode.objectify.Key.create(urlSafe), vKeyProperty);
  }

  @Override
  public SimpleTranslator<VKey, Key> createTranslator(Property property) {
    return new SimpleTranslator<VKey, Key>() {
      @Override
      public VKey loadValue(Key datastoreValue) {
        return createVKey(datastoreValue, property);
      }

      @Override
      public Key saveValue(VKey key) {
        return key == null ? null : key.getOfyKey().getRaw();
      }
    };
  }
}

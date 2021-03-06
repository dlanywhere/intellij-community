// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.configurationStore.xml

import com.intellij.util.xmlb.annotations.OptionTag
import com.intellij.util.xmlb.annotations.Tag
import com.intellij.util.xmlb.annotations.XCollection
import org.junit.Test

class XmlSerializerListTest {
  @Test
  fun notFinalField() {
    @Tag("bean")
    class Bean {
      @JvmField
      var values = arrayListOf("a", "b", "w")
    }

    val bean = Bean()
    check(bean) {
      bean.values = it
    }
  }

  @Test
  fun notFinalProperty() {
    @Tag("bean")
    class Bean {
      var values = arrayListOf("a", "b", "w")
    }

    val bean = Bean()
    check(bean) {
      bean.values = it
    }
  }

  @Test
  fun finalField() {
    @Tag("bean")
    class Bean {
      @JvmField
      val values = arrayListOf("a", "b", "w")
    }

    val bean = Bean()
    check(bean) {
      bean.values.clear()
      bean.values.addAll(it)
    }
  }

  @Test
  fun finalProperty() {
    @Tag("bean")
    class Bean {
      @OptionTag
      val values = arrayListOf("a", "b", "w")
    }

    val bean = Bean()
    check(bean) {
      bean.values.clear()
      bean.values.addAll(it)
    }
  }

  @Test
  fun finalPropertyWithoutWrapping() {
    @Tag("bean")
    class Bean {
      @XCollection
      val values = arrayListOf("a", "b", "w")
    }

    val bean = Bean()
    testSerializer("""
    <bean>
      <option name="values">
        <option value="a" />
        <option value="b" />
        <option value="w" />
      </option>
    </bean>""", bean)

    bean.values.clear()
    bean.values.addAll(arrayListOf("1", "2", "3"))

    testSerializer("""
    <bean>
      <option name="values">
        <option value="1" />
        <option value="2" />
        <option value="3" />
      </option>
    </bean>""", bean)
  }

  private fun <T : Any> check(bean: T, setter: (values: ArrayList<String>) -> Unit) {
    testSerializer("""
      <bean>
        <option name="values">
          <list>
            <option value="a" />
            <option value="b" />
            <option value="w" />
          </list>
        </option>
      </bean>""", bean)
    setter(arrayListOf("1", "2", "3"))

    testSerializer("""
      <bean>
        <option name="values">
          <list>
            <option value="1" />
            <option value="2" />
            <option value="3" />
          </list>
        </option>
      </bean>""", bean)
  }
}
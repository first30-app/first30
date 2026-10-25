package com.first30.core

/**
 * JS 값 모델의 표지(marker). 엔진 이식은 JS 값을 `null`/Boolean/Double/String/List/Map 으로 다루고,
 * JS 에만 있는 두 가지를 아래로 나타낸다.
 */

/** JS `undefined` — 키가 "있지만 값이 undefined" 인 경우(키 자체가 없으면 Map 에 넣지 않는다). */
object JsUndefined {
    override fun toString(): String = "undefined"
}

/** JS 함수 값. stableStringify 는 객체에서 함수 값 키를 뺀다 (engine.js 1627행). */
interface JsFunction {
    val name: String
}

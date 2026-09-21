package com.aplnist.dwz.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UrlUtilsTest {
	@Test
	void checkURL_acceptsUrlWithProtocol() {
		assertTrue(UrlUtils.checkURL("https://www.baidu.com"));
	}

	@Test
	void checkURL_acceptsUrlWithoutProtocol() {
		assertTrue(UrlUtils.checkURL("www.baidu.com"));
	}

	@Test
	void checkURL_acceptsUrlWithPathAndQuery() {
		assertTrue(UrlUtils.checkURL("https://example.com/a/b?x=1#frag"));
	}

	@Test
	void checkURL_rejectsPlainText() {
		assertFalse(UrlUtils.checkURL("abc"));
	}

	@Test
	void checkURL_rejectsEmptyString() {
		assertFalse(UrlUtils.checkURL(""));
	}

	@Test
	void checkURL_rejectsHostWithoutDot() {
		assertFalse(UrlUtils.checkURL("localhost"));
	}
}

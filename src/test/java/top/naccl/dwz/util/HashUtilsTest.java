package top.naccl.dwz.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HashUtilsTest {
	private static final String BASE62_CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

	@Test
	void hashToBase62_isNonEmpty() {
		assertFalse(HashUtils.hashToBase62("https://www.baidu.com").isEmpty());
	}

	@Test
	void hashToBase62_lengthBetween1And6() {
		String[] urls = {
				"https://www.baidu.com",
				"https://example.com/a/b/c?x=1&y=2",
				"http://localhost:8080/path",
				"https://github.com/naccl/dwz"
		};
		for (String url : urls) {
			int len = HashUtils.hashToBase62(url).length();
			assertTrue(len >= 1 && len <= 6, "length out of range for " + url + ": " + len);
		}
	}

	@Test
	void hashToBase62_onlyContainsBase62Chars() {
		String code = HashUtils.hashToBase62("https://example.com/path?query=1#frag");
		for (char c : code.toCharArray()) {
			assertTrue(BASE62_CHARS.indexOf(c) >= 0, "unexpected char: " + c);
		}
	}

	@Test
	void hashToBase62_isDeterministic() {
		String url = "https://www.google.com/search?q=short+url";
		assertEquals(HashUtils.hashToBase62(url), HashUtils.hashToBase62(url));
	}

	@Test
	void hashToBase62_differentInputsProduceDifferentCodes() {
		assertNotEquals(
				HashUtils.hashToBase62("https://www.baidu.com"),
				HashUtils.hashToBase62("https://www.google.com"));
	}
}

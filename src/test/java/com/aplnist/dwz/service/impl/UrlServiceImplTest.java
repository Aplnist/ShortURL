package com.aplnist.dwz.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.redisson.api.RBloomFilter;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import com.aplnist.dwz.entity.UrlMap;
import com.aplnist.dwz.mapper.UrlMapper;
import com.aplnist.dwz.util.HashUtils;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UrlServiceImplTest {
	@Mock
	UrlMapper urlMapper;
	@Mock
	StringRedisTemplate redisTemplate;
	@Mock
	RedisScript<Long> releaseLockScript;
	@Mock
	RBloomFilter<String> bloomFilter;
	@Mock
	ValueOperations<String, String> valueOperations;

	@InjectMocks
	UrlServiceImpl urlService;

	@BeforeEach
	void setUp() {
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
	}

	// ==================== saveUrlMap ====================

	@Test
	void saveUrlMap_insertsWhenBloomFilterMiss() {
		String longURL = "https://example.com";
		String shortURL = HashUtils.hashToBase62(longURL);
		when(bloomFilter.contains(shortURL)).thenReturn(false);

		String result = urlService.saveUrlMap(shortURL, longURL, longURL);

		assertEquals(shortURL, result);
		verify(urlMapper).saveUrlMap(any(UrlMap.class));
		verify(bloomFilter).add(shortURL);
		verify(valueOperations).set(eq(shortURL), eq(longURL), eq(10L), eq(TimeUnit.MINUTES));
	}

	@Test
	void saveUrlMap_returnsSameWhenRedisHasSameLongUrl() {
		String longURL = "https://example.com";
		String shortURL = HashUtils.hashToBase62(longURL);
		when(bloomFilter.contains(shortURL)).thenReturn(true);
		when(valueOperations.get(shortURL)).thenReturn(longURL);

		String result = urlService.saveUrlMap(shortURL, longURL, longURL);

		assertEquals(shortURL, result);
		verify(urlMapper, never()).getLongUrlByShortUrl(any());
		verify(urlMapper, never()).saveUrlMap(any(UrlMap.class));
		verify(redisTemplate).expire(shortURL, 10, TimeUnit.MINUTES);
	}

	@Test
	void saveUrlMap_returnsSameWhenDbHasSameLongUrl() {
		String longURL = "https://example.com";
		String shortURL = HashUtils.hashToBase62(longURL);
		when(bloomFilter.contains(shortURL)).thenReturn(true);
		when(valueOperations.get(shortURL)).thenReturn(null);
		when(urlMapper.getLongUrlByShortUrl(shortURL)).thenReturn(longURL);

		String result = urlService.saveUrlMap(shortURL, longURL, longURL);

		assertEquals(shortURL, result);
		verify(urlMapper, never()).saveUrlMap(any(UrlMap.class));
		verify(valueOperations).set(eq(shortURL), eq(longURL), eq(10L), eq(TimeUnit.MINUTES));
	}

	@Test
	void saveUrlMap_rehashOnRealCollision() {
		String longURL = "https://example.com";
		String shortURL = HashUtils.hashToBase62(longURL);
		String rehashed = HashUtils.hashToBase62(longURL + "*");
		when(bloomFilter.contains(shortURL)).thenReturn(true);
		when(bloomFilter.contains(rehashed)).thenReturn(false);
		when(valueOperations.get(shortURL)).thenReturn(null);
		when(urlMapper.getLongUrlByShortUrl(shortURL)).thenReturn("https://other.com");

		String result = urlService.saveUrlMap(shortURL, longURL, longURL);

		assertEquals(rehashed, result);
		verify(urlMapper).saveUrlMap(argThat(m -> rehashed.equals(m.getSurl())));
	}

	@Test
	void saveUrlMap_retryOnDuplicateKey() {
		String longURL = "https://example.com";
		String shortURL = HashUtils.hashToBase62(longURL);
		String rehashed = HashUtils.hashToBase62(longURL + "*");
		when(bloomFilter.contains(shortURL)).thenReturn(false);
		when(bloomFilter.contains(rehashed)).thenReturn(false);
		doThrow(new DuplicateKeyException("dup"))
				.when(urlMapper).saveUrlMap(argThat(m -> shortURL.equals(m.getSurl())));

		String result = urlService.saveUrlMap(shortURL, longURL, longURL);

		assertEquals(rehashed, result);
		verify(urlMapper, times(2)).saveUrlMap(any(UrlMap.class));
	}

	@Test
	void saveUrlMap_rehashWhenShortUrlLengthIsOne() {
		String longURL = "https://example.com";
		String rehashed = HashUtils.hashToBase62(longURL + "*");
		when(bloomFilter.contains(rehashed)).thenReturn(false);

		String result = urlService.saveUrlMap("a", longURL, longURL);

		assertEquals(rehashed, result);
	}

	// ==================== getLongUrlByShortUrl ====================

	@Test
	void getLongUrl_returnsNullWhenBloomFilterMiss() {
		when(bloomFilter.contains("notexist")).thenReturn(false);

		assertNull(urlService.getLongUrlByShortUrl("notexist"));
		verify(urlMapper, never()).getLongUrlByShortUrl(any());
	}

	@Test
	void getLongUrl_returnsFromCacheWhenHit() {
		String shortURL = "abc123";
		String longURL = "https://example.com";
		when(bloomFilter.contains(shortURL)).thenReturn(true);
		when(valueOperations.get(shortURL)).thenReturn(longURL);

		String result = urlService.getLongUrlByShortUrl(shortURL);

		assertEquals(longURL, result);
		verify(redisTemplate).expire(shortURL, 10, TimeUnit.MINUTES);
		verify(urlMapper, never()).getLongUrlByShortUrl(any());
	}

	@Test
	void getLongUrl_returnsNullWhenCachedEmpty() {
		String shortURL = "abc123";
		when(bloomFilter.contains(shortURL)).thenReturn(true);
		when(valueOperations.get(shortURL)).thenReturn("");

		assertNull(urlService.getLongUrlByShortUrl(shortURL));
		verify(urlMapper, never()).getLongUrlByShortUrl(any());
	}

	@Test
	void getLongUrl_queriesDbWhenCacheMissAndFillsCache() {
		String shortURL = "abc123";
		String longURL = "https://example.com";
		when(bloomFilter.contains(shortURL)).thenReturn(true);
		when(valueOperations.get(shortURL)).thenReturn(null);
		when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any())).thenReturn(true);
		when(urlMapper.getLongUrlByShortUrl(shortURL)).thenReturn(longURL);

		String result = urlService.getLongUrlByShortUrl(shortURL);

		assertEquals(longURL, result);
		verify(valueOperations).set(eq(shortURL), eq(longURL), eq(10L), eq(TimeUnit.MINUTES));
	}

	@Test
	void getLongUrl_cachesEmptyWhenDbMiss() {
		String shortURL = "abc123";
		when(bloomFilter.contains(shortURL)).thenReturn(true);
		when(valueOperations.get(shortURL)).thenReturn(null);
		when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any())).thenReturn(true);
		when(urlMapper.getLongUrlByShortUrl(shortURL)).thenReturn(null);

		assertNull(urlService.getLongUrlByShortUrl(shortURL));
		verify(valueOperations).set(eq(shortURL), eq(""), eq(1L), eq(TimeUnit.MINUTES));
	}
}

package com.aplnist.dwz.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.springframework.stereotype.Repository;
import com.aplnist.dwz.entity.UrlMap;

import java.util.List;

/**
 * @Description: 长短链接映射持久层接口
 * @Author: Aplnist
 * @Date: 2021-03-22
 */
@Mapper
@Repository
public interface UrlMapper {
	String getLongUrlByShortUrl(String surl);

	int saveUrlMap(UrlMap urlMap);

	int updateUrlViews(String surl);

	List<String> getAllShortUrls();
}

package com.aplnist.dwz;

import org.redisson.api.RBloomFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import com.aplnist.dwz.mapper.UrlMapper;

@EnableAsync
@SpringBootApplication
public class DwzApplication implements ApplicationRunner {
	@Autowired
	UrlMapper urlMapper;
	@Autowired
	RBloomFilter<String> bloomFilter;

	public static void main(String[] args) {
		SpringApplication.run(DwzApplication.class, args);
	}

	//Spring容器就绪后，若Redis布隆过滤器为空则从数据库重建，避免每次重启全量扫描
	@Override
	public void run(ApplicationArguments args) {
		if (bloomFilter.count() == 0) {
			for (String surl : urlMapper.getAllShortUrls()) {
				bloomFilter.add(surl);
			}
		}
	}
}

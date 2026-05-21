package com.vsec.repository;

import com.vsec.entity.VideoKeyword;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface VideoKeywordRepository extends JpaRepository<VideoKeyword, Long> {

    List<VideoKeyword> findByKeywordHash(byte[] keywordHash);

    void deleteByVideoId(String videoId);

    long countByVideoId(String videoId);
}

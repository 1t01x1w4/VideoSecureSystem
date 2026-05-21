package com.vsec.repository;

import com.vsec.entity.Video;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface VideoRepository extends JpaRepository<Video, String> {

    List<Video> findByUuidOrderByCreatedAtDesc(String uuid);

    long countByUuid(String uuid);

    @Query("SELECT COALESCE(SUM(v.size), 0) FROM Video v WHERE v.uuid = :uuid")
    long totalSizeByUuid(@Param("uuid") String uuid);

    List<Video> findByUuidAndVideoIdInOrderByCreatedAtDesc(String uuid, Collection<String> videoIds);
}

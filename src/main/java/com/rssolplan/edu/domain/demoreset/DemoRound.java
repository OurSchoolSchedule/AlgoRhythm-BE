package com.rssolplan.edu.domain.demoreset;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Entity
@Table(name = "demo_reset_round")
class DemoResetState {
  @Id private Integer id;
  private long revision;
}

/** Retain the deployed namespace for existing authentication flows. */
@Component
@RequiredArgsConstructor
public class DemoRound {
  private final JdbcTemplate db;

  public long revision() {
    return db.queryForObject("select coalesce(max(revision),0) from demo_reset_round", Long.class);
  }

  public String prefix() {
    long revision = revision();
    return revision == 0 ? "" : "demo-round:" + revision + ":";
  }

  // Keep email proof and account creation on the same side of a concurrent reset.
  public void lockForVerification() {
    db.queryForList("select id from demo_reset_round where id=1 lock in share mode", Integer.class);
  }

  @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
  public void initialize() {
    lock();
  }

  void lock() {
    db.update(
        "insert into demo_reset_round(id,revision) values (1,0) on duplicate key update id=1");
  }
}

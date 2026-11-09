package dev.alvo.pieria.api.response;

import java.util.List;

public record HarvesterDoctorResponse(List<Entry> entries) {

  public record Entry(String directory, boolean ok, String detail) {
  }
}

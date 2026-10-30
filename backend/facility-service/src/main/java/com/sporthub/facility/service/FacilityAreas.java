package com.sporthub.facility.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.text.Normalizer;
import java.util.*;

/**
 * Former districts mapped to the wards that replaced them on 2025-07-01. Addresses only store the
 * current province and ward, yet people still search by the districts they know.
 */
public final class FacilityAreas {
  public record Ward(String code, String name) {}

  public record District(String provinceCode, String name, List<Ward> wards) {}

  public record Match(District district, String remainder) {}

  private static final List<District> DISTRICTS = load();
  private static final String LETTERS =
      "àáảãạăằắẳẵặâầấẩẫậèéẻẽẹêềếểễệìíỉĩịòóỏõọôồốổỗộơờớởỡợùúủũụưừứửữựỳýỷỹỵđ";
  private static final String PLAIN =
      "a".repeat(17) + "e".repeat(11) + "i".repeat(5) + "o".repeat(17) + "u".repeat(11) + "y".repeat(5) + "d";

  private FacilityAreas() {}

  private static List<District> load() {
    try (InputStream in = FacilityAreas.class.getResourceAsStream("/legacy-districts.json")) {
      return List.of(new ObjectMapper().readValue(in, District[].class));
    } catch (IOException ex) {
      throw new IllegalStateException("Cannot read the former district table", ex);
    }
  }

  public static List<District> ofProvince(String provinceCode) {
    return DISTRICTS.stream().filter(d -> d.provinceCode().equals(provinceCode)).toList();
  }

  public static Optional<District> find(String provinceCode, String name) {
    String wanted = plain(name);
    return DISTRICTS.stream()
        .filter(d -> provinceCode == null || d.provinceCode().equals(provinceCode))
        .filter(d -> plain(d.name()).equals(wanted))
        .findFirst();
  }

  /** Finds a former district named inside free text, such as "san quan Ha Dong". */
  public static Optional<Match> inText(String provinceCode, String text) {
    String value = " " + plain(text) + " ";
    return DISTRICTS.stream()
        .filter(d -> provinceCode == null || d.provinceCode().equals(provinceCode))
        .sorted(Comparator.comparingInt((District d) -> -d.name().length()))
        .filter(d -> value.contains(" " + plain(d.name()) + " "))
        .findFirst()
        .map(
            d -> {
              String rest = value.replace(" " + plain(d.name()) + " ", " ");
              rest = rest.replaceAll(" (quan|huyen|thi xa|tx|q) ", " ");
              return new Match(d, rest.trim());
            });
  }

  /** Lower case, no diacritics; matches {@link #plainSql(String)} on stored text. */
  public static String plain(String value) {
    return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
        .replaceAll("\\p{M}+", "")
        .replace('đ', 'd')
        .replace('Đ', 'd')
        .toLowerCase(Locale.ROOT)
        .replaceAll("\\s+", " ")
        .trim();
  }

  static String plainSql(String column) {
    return "translate(lower(" + column + "),'" + LETTERS + "','" + PLAIN + "')";
  }

  static String wardSql() {
    return "regexp_replace(" + plainSql("f.ward") + ",'^(phuong|xa|dac khu|thi tran) ','')";
  }
}

package com.caradvice.scraper;

import com.caradvice.model.ExpertInsight;
import com.caradvice.service.ExpertInsightService;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tester för Mobility Sweden-månadssynken: xlsx-parsning av rankingarken,
 * namnnormalisering, rapportupptäckt i HTML och ersättningslogiken.
 *
 * @author Robert Andersson Kopler
 */
@ExtendWith(MockitoExtension.class)
class MobilityStatsSyncServiceTest {

    @Mock
    private ExpertInsightService insightService;

    private MobilityStatsSyncService service() {
        return new MobilityStatsSyncService(insightService, mock(JobStatusService.class));
    }

    // ── Testdata: bygger en xlsx i minnet med samma struktur som riktiga rapporten ──

    private static byte[] workbook(String period, Object[][] totalRows, Object[][] evRows) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            fillSheet(wb.createSheet("PB - Rankinglista"), period, totalRows);
            fillSheet(wb.createSheet("Elbil ranking"), period, evRows);
            wb.write(out);
            return out.toByteArray();
        }
    }

    private static void fillSheet(Sheet s, String period, Object[][] rows) {
        s.createRow(1).createCell(7).setCellValue("Nyregistreringar " + period);
        Row head = s.createRow(7);
        head.createCell(3).setCellValue("Modell (Antal reg. YTD↓ )");
        head.createCell(12).setCellValue("Ackumulerat");
        int r = 9;
        for (Object[] row : rows) {
            Row dataRow = s.createRow(r++);
            dataRow.createCell(3).setCellValue((String) row[0]);
            dataRow.createCell(5).setCellValue((int) row[1]);
            dataRow.createCell(12).setCellValue((int) row[2]);
        }
    }

    // ── parseReport ──

    @Test
    void parsarLedareOchElbilsledareMedSvenskaAntal() throws Exception {
        byte[] xlsx = workbook("juni 2026",
                new Object[][]{{"VOLVO XC60", 2092, 7594}, {"VOLVO EX/XC40", 1261, 7389}},
                new Object[][]{{"VOLVO EX/XC40", 982, 6275}, {"TESLA MODEL Y", 1365, 4862}});

        List<ExpertInsight> insights = service().parseReport(xlsx);

        assertThat(insights).hasSize(2);
        assertThat(insights.get(0).getCarMake()).isEqualTo("Volvo");
        assertThat(insights.get(0).getCarModel()).isEqualTo("XC60");
        assertThat(insights.get(0).getInsight())
                .contains("januari–juni 2026").contains("7 594").contains("mest registrerade bil");
        assertThat(insights.get(1).getCarModel()).isEqualTo("EX40");
        assertThat(insights.get(1).getFuelType()).isEqualTo("elbil");
        assertThat(insights.get(1).getInsight()).contains("6 275").contains("före Tesla Model Y");
    }

    @Test
    void elbilsrankingenGerTioRaderHittillsIAr() throws Exception {
        Object[][] ev = new Object[12][];
        ev[0] = new Object[]{"VOLVO EX/XC40", 982, 8897};
        ev[1] = new Object[]{"TESLA MODEL Y", 2330, 7100};
        for (int i = 2; i < ev.length; i++) ev[i] = new Object[]{"KIA EV" + i, 100, 5000 - i * 100};
        byte[] xlsx = workbook("september 2026", new Object[][]{{"VOLVO XC60", 1200, 10669}}, ev);

        MobilityStatsSyncService.Elbilsranking r = MobilityStatsSyncService.parseElbilsranking(xlsx);

        assertThat(r.period()).isEqualTo("januari–september 2026");
        assertThat(r.kalla()).isEqualTo("Mobility Sweden");
        assertThat(r.rader()).hasSize(MobilityStatsSyncService.RANKING_ANTAL);
        assertThat(r.rader().get(0)).isEqualTo(new MobilityStatsSyncService.RankingRad(1, "Volvo EX40", 8897));
        assertThat(r.rader().get(1).modell()).isEqualTo("Tesla Model Y");
        assertThat(r.rader().get(9).plats()).isEqualTo(10);
    }

    @Test
    void elbilsrankingenUtanElbilsarkGerNull() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            wb.createSheet("Fel ark");
            wb.write(out);
            assertThat(MobilityStatsSyncService.parseElbilsranking(out.toByteArray())).isNull();
        }
    }

    @Test
    void elbilsrankingenHamtasEnGangOchCachas() throws Exception {
        byte[] xlsx = workbook("september 2026", new Object[][]{{"VOLVO XC60", 1200, 10669}},
                new Object[][]{{"VOLVO EX/XC40", 982, 8897}});
        int[] hamtningar = {0};
        MobilityStatsSyncService s = new MobilityStatsSyncService(insightService, mock(JobStatusService.class)) {
            @Override String findLatestReportUrl() { return "https://mobilitysweden.se/x.xlsx"; }
            @Override byte[] download(String url) { hamtningar[0]++; return xlsx; }
        };

        assertThat(s.elbilsranking().rader().get(0).antal()).isEqualTo(8897);
        assertThat(s.elbilsranking()).isNotNull();
        assertThat(hamtningar[0]).isEqualTo(1);
    }

    @Test
    void elbilsrankingenNarRapportenSaknasGerNull() {
        MobilityStatsSyncService s = new MobilityStatsSyncService(insightService, mock(JobStatusService.class)) {
            @Override String findLatestReportUrl() { return null; }
        };
        assertThat(s.elbilsranking()).isNull();
    }

    /**
     * Rapporten säger inte vilken hylla bilen står på. Utan uppslaget bär totalettans rad varken
     * kategori eller drivmedel, och når då aldrig rekommendationsprompten.
     */
    @Test
    void radernaFarKategoriUrTabellensOvrigaRader() throws Exception {
        when(insightService.kategoriForModell("Volvo", "XC60", "Mobility Sweden månadsläget"))
                .thenReturn("suv");
        when(insightService.kategoriForModell("Volvo", "EX40", "Mobility Sweden månadsläget"))
                .thenReturn(null);
        byte[] xlsx = workbook("juni 2026",
                new Object[][]{{"VOLVO XC60", 2092, 7594}},
                new Object[][]{{"VOLVO EX/XC40", 982, 6275}});

        List<ExpertInsight> insights = service().parseReport(xlsx);

        assertThat(insights.get(0).getCategory()).isEqualTo("suv");
        assertThat(insights.get(1).getCategory()).isNull();
        assertThat(insights.get(1).getFuelType()).isEqualTo("elbil");
    }

    @Test
    void manadensEttaFarEgenRadNarDenSkiljerSigFranYtdLedaren() throws Exception {
        byte[] xlsx = workbook("juni 2026",
                new Object[][]{{"VOLVO XC60", 1000, 7594}, {"TESLA MODEL Y", 2500, 4862}},
                new Object[][]{{"VOLVO EX/XC40", 982, 6275}});

        List<ExpertInsight> insights = service().parseReport(xlsx);

        assertThat(insights).hasSize(3);
        assertThat(insights.get(2).getCarMake()).isEqualTo("Tesla");
        assertThat(insights.get(2).getInsight()).contains("juni 2026").contains("2 500");
    }

    @Test
    void saknatArkGerTomLista() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            wb.createSheet("Fel ark");
            wb.write(out);
            assertThat(service().parseReport(out.toByteArray())).isEmpty();
        }
    }

    // ── Namnnormalisering ──

    @Test
    void normaliserarRapportensVersalaModellnamn() {
        assertThat(MobilityStatsSyncService.normalizeName("VOLVO XC60")).containsExactly("Volvo", "XC60");
        assertThat(MobilityStatsSyncService.normalizeName("VOLVO EX/XC40")).containsExactly("Volvo", "EX40");
        assertThat(MobilityStatsSyncService.normalizeName("VW ID.7/ID.7 TOURER")).containsExactly("Volkswagen", "ID.7");
        assertThat(MobilityStatsSyncService.normalizeName("TESLA MODEL Y")).containsExactly("Tesla", "Model Y");
        assertThat(MobilityStatsSyncService.normalizeName("BMW IX3")).containsExactly("BMW", "iX3");
        assertThat(MobilityStatsSyncService.normalizeName("BMW IX")).containsExactly("BMW", "iX");
        assertThat(MobilityStatsSyncService.normalizeName("BMW I4")).containsExactly("BMW", "i4");
        assertThat(MobilityStatsSyncService.normalizeName("BMW X1")).containsExactly("BMW", "X1");
        assertThat(MobilityStatsSyncService.normalizeName("MERCEDES-BENZ CLA")).containsExactly("Mercedes", "CLA");
        assertThat(MobilityStatsSyncService.normalizeName("SKODA KODIAQ")).containsExactly("Skoda", "Kodiaq");
    }

    @Test
    void periodRangeSlarIhopJanuariTillAktuellManad() {
        assertThat(MobilityStatsSyncService.periodRange("juni 2026")).isEqualTo("januari–juni 2026");
        assertThat(MobilityStatsSyncService.periodRange("januari 2026")).isEqualTo("januari 2026");
        assertThat(MobilityStatsSyncService.periodRange(null)).isNull();
    }

    // ── Upptäckt ──

    @Test
    void extraherarArtikellankarForRattArIOrdningUtanDubbletter() {
        String html = """
                <a href="/statistik/Nyregistreringar_per_manad_1/nyregistreringar-2026/februari-rapport">feb</a>
                <a href="/statistik/Nyregistreringar_per_manad_1/nyregistreringar-2026/juli-rapport">jul</a>
                <a href="/statistik/Nyregistreringar_per_manad_1/nyregistreringar-2026/juli-rapport">jul igen</a>
                <a href="/statistik/Nyregistreringar_per_manad_1/nyregistreringar-2025/gammal">fel år</a>
                <a href="/om-oss">annat</a>
                """;
        List<String> urls = MobilityStatsSyncService.extractArticleUrls(html, 2026);
        assertThat(urls).containsExactly(
                "https://mobilitysweden.se/statistik/Nyregistreringar_per_manad_1/nyregistreringar-2026/februari-rapport",
                "https://mobilitysweden.se/statistik/Nyregistreringar_per_manad_1/nyregistreringar-2026/juli-rapport");
    }

    @Test
    void extraherarXlsxLankenMenIntePdf() {
        String html = """
                <a href="/storage/abc/pdf/media/x/Rapport.pdf">pdf</a>
                <a href="/storage/abc/xlsx/media/x/M%C3%A5nadsrapport%20Nyregistreringar%20juni%202026.xlsx">xlsx</a>
                """;
        assertThat(MobilityStatsSyncService.extractXlsxUrl(html))
                .isEqualTo("https://mobilitysweden.se/storage/abc/xlsx/media/x/M%C3%A5nadsrapport%20Nyregistreringar%20juni%202026.xlsx");
        assertThat(MobilityStatsSyncService.extractXlsxUrl("<a href=\"/x.pdf\">bara pdf</a>")).isNull();
    }

    // ── syncNow (nät stubbas via överlagring) ──

    private MobilityStatsSyncService stubbed(byte[] xlsx) {
        return new MobilityStatsSyncService(insightService, mock(JobStatusService.class)) {
            @Override
            String findLatestReportUrl() {
                return xlsx == null ? null : "https://mobilitysweden.se/storage/rapport.xlsx";
            }

            @Override
            byte[] download(String url) {
                return xlsx;
            }
        };
    }

    @Test
    void syncNowErsatterManadsraderna() throws Exception {
        byte[] xlsx = workbook("juni 2026",
                new Object[][]{{"VOLVO XC60", 2092, 7594}},
                new Object[][]{{"VOLVO EX/XC40", 982, 6275}});

        Map<String, Object> result = stubbed(xlsx).syncNow();

        assertThat(result.get("status")).isEqualTo("OK");
        assertThat(result.get("imported")).isEqualTo(2);
        verify(insightService).deleteByExpert(MobilityStatsSyncService.EXPERT_NAME);
        verify(insightService, times(2)).save(any(ExpertInsight.class));
    }

    @Test
    void syncNowUtanRapportRorInteDatabasen() {
        Map<String, Object> result = stubbed(null).syncNow();

        assertThat(result.get("status")).isEqualTo("ERROR");
        verify(insightService, never()).deleteByExpert(any());
        verify(insightService, never()).save(any());
    }

    // ── Drivmedelsarket: elbilsandelen till karusellen (2026-11-10) ──

    /**
     * "PB - Drivmedel" byggd cell för cell som oktoberrapporten 2026: perioden i N1, rubrikerna
     * på rad 9 med "Antal " (med mellanslag) i I, SPLY i M, YTD i S — och samma rubriker en
     * gång till längre till höger för andelarna (AC, AD), som INTE får väljas. Under Totalt
     * kommer månadstabellen, där "EL - Personbilar per månad" inte är EL-raden.
     */
    private static byte[] drivmedelsark(boolean medTotalt) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet s = wb.createSheet("PB - Drivmedel");
            s.createRow(0).createCell(13).setCellValue("Nyregistreringar oktober 2026");
            Row rubrik = s.createRow(8);
            Object[][] rubriker = {{8, "Antal "}, {12, "SPLY"}, {16, "Δ%"}, {18, "YTD"}, {21, "YTD LY"},
                    {26, "okt"}, {28, "SPLY"}, {29, "YTD"}};
            for (Object[] r : rubriker) rubrik.createCell((Integer) r[0]).setCellValue((String) r[1]);
            Object[][] rader = {
                    {"EL", 12594, 8707, 103450, 0.5107, 0.3616, 0.4375},
                    {"Laddhybrid", 5376, 7111, 56046, 0.2180, 0.2953, 0.2370},
                    {"Bensin", 4037, 4717, 44303, 0.1637, 0.1959, 0.1874},
                    {"Totalt", 24660, 24078, 236446, 1.0, 1.0, 1.0}};
            int nr = 9;
            for (Object[] r : rader) {
                if (!medTotalt && r[0].equals("Totalt")) continue;
                Row row = s.createRow(nr++);
                row.createCell(4).setCellValue((String) r[0]);
                row.createCell(8).setCellValue((Integer) r[1]);
                row.createCell(12).setCellValue((Integer) r[2]);
                row.createCell(18).setCellValue((Integer) r[3]);
                row.createCell(26).setCellValue((Double) r[4]);
                row.createCell(28).setCellValue((Double) r[5]);
                row.createCell(29).setCellValue((Double) r[6]);
            }
            s.createRow(23).createCell(2).setCellValue("EL - Personbilar per månad");
            Row manad = s.createRow(36);
            manad.createCell(4).setCellValue("oktober");
            manad.createCell(11).setCellValue(12594);
            wb.write(out);
            return out.toByteArray();
        }
    }

    @Test
    void drivmedelsarketGerOktobersAndelarSomPressmeddelandet() throws Exception {
        MobilityStatsSyncService.Drivmedelslage l = MobilityStatsSyncService.parseDrivmedel(drivmedelsark(true));

        assertThat(l).isEqualTo(new MobilityStatsSyncService.Drivmedelslage(
                "oktober 2026", 12594, 5376, 24660, 8707, 24078, 103450, 236446));
        // Pressmeddelandet 2026-11-04: "från 36 procent till 51 procent", "44 procent hittills i år"
        assertThat(l.text()).isEqualTo("I oktober 2026 var **51 %** av Sveriges nya personbilar elbilar, "
                + "upp från 36 % ett år tidigare, medan laddhybriderna låg på 22 %. "
                + "Hittills i år ligger elbilsandelen på **44 %**.");
        assertThat(l.datum()).hasToString("2026-11-01");
    }

    @Test
    void decemberrapportenDaterasJanuariNastaAr() {
        var l = new MobilityStatsSyncService.Drivmedelslage("december 2026", 1, 1, 2, 1, 2, 1, 2);
        assertThat(l.datum()).hasToString("2027-01-01");
    }

    @Test
    void lagreAndelAnIFjolSagsSomNedgang() {
        var l = new MobilityStatsSyncService.Drivmedelslage("mars 2027", 400, 100, 1000, 500, 1000, 450, 1000);
        assertThat(l.text()).contains("**40 %**").contains("ned från 50 %");
    }

    @Test
    void drivmedelsarkUtanTotalradGerNullInteEnGissning() throws Exception {
        assertThat(MobilityStatsSyncService.parseDrivmedel(drivmedelsark(false))).isNull();
    }

    @Test
    void rapportUtanDrivmedelsarkGerNull() throws Exception {
        byte[] xlsx = workbook("juni 2026", new Object[][]{{"VOLVO XC60", 1200, 10669}},
                new Object[][]{{"VOLVO EX/XC40", 900, 5000}});
        assertThat(MobilityStatsSyncService.parseDrivmedel(xlsx)).isNull();
    }

    @Test
    void syncenFyllerDrivmedelslaget() throws Exception {
        byte[] ark = drivmedelsark(true);
        MobilityStatsSyncService s = new MobilityStatsSyncService(insightService, mock(JobStatusService.class)) {
            @Override String findLatestReportUrl() { return "https://mobilitysweden.se/storage/rapport.xlsx"; }
            @Override byte[] download(String url) { return ark; }
            @Override List<ExpertInsight> parseReport(byte[] xlsx) {
                return List.of(new ExpertInsight(EXPERT_NAME, "Volvo", "XC60", null, null, "x", null));
            }
        };
        s.syncNow();
        // Ingen ny hämtning behövs: läget fylldes av synken och är färskt.
        assertThat(s.drivmedelslage()).isNotNull().extracting(MobilityStatsSyncService.Drivmedelslage::el).isEqualTo(12594);
    }
}

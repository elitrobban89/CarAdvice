/* CarAdvice — (c) 2026 Robert Andersson Kopler. Alla rattigheter forbehallna. */
/*
 * Prov för versionskollen (scripts/versionskoll.js), de rena delarna, utan nät.
 *
 * Kör:  node src/test/js/versionskoll-prov.js
 *
 * Det som provas är det som gav fel svar när kollen skrevs (2026-11-10):
 *   · maven-metadata.xml:s <release> var 4.2.0-RC2 för Spring Boot och 28-ea+12 för JavaFX,
 *     förhandsversioner som aldrig ska föreslås,
 *   · byte-buddy har "-jdk5"-varianter som sorterar över den riktiga versionen,
 *   · "1.10" ska vara större än "1.9" (textjämförelse ger fel),
 *   · ${egenskaper} i pom.xml ska lösas upp, och en version som styrs av parent hoppas över.
 */
const v = require('../../../scripts/versionskoll.js');

let fel = 0;
function prov(namn, ok, detalj) {
  console.log((ok ? '  ok    ' : '  FEL   ') + namn + (ok ? '' : '\n          ' + detalj));
  if (!ok) fel++;
}

const meta = vs => '<metadata><versioning><release>' + vs[vs.length - 1] + '</release><versions>'
  + vs.map(x => '<version>' + x + '</version>').join('') + '</versions></versioning></metadata>';

prov('RC räknas inte', v.senasteStabila(meta(['4.1.1', '4.1.2', '4.2.0-M1', '4.2.0-RC2'])) === '4.1.2', v.senasteStabila(meta(['4.1.1', '4.1.2', '4.2.0-RC2'])));
prov('early access räknas inte', v.senasteStabila(meta(['27', '27.0.1', '28-ea+12'])) === '27.0.1', v.senasteStabila(meta(['27', '27.0.1', '28-ea+12'])));
prov('-jdk5 räknas inte', v.senasteStabila(meta(['1.18.14', '1.18.17', '1.18.17-jdk5'])) === '1.18.17', v.senasteStabila(meta(['1.18.17', '1.18.17-jdk5'])));
prov('beta räknas inte', v.senasteStabila(meta(['34.0.0', '34.1.0', '34.2.0-beta.1'])) === '34.1.0', '');
prov('1.10 > 1.9', v.jamfor('1.10.0', '1.9.3') > 0, v.jamfor('1.10.0', '1.9.3'));
prov('fyrdelad sqlite-version', v.jamfor('3.54.1.0', '3.53.4.0') > 0, '');
prov('lika är lika', v.jamfor('6.1.3', '6.1.3') === 0, '');

prov('steg major', v.steg('5.5.1', '6.0.0') === 'major', v.steg('5.5.1', '6.0.0'));
prov('steg minor', v.steg('1.23.2', '1.24.1') === 'minor', v.steg('1.23.2', '1.24.1'));
prov('steg patch', v.steg('4.1.1', '4.1.2') === 'patch', v.steg('4.1.1', '4.1.2'));
prov('kort version: 27 → 27.0.1 är patch', v.steg('27', '27.0.1') === 'patch', v.steg('27', '27.0.1'));

const pom = `<project>
  <parent><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-parent</artifactId><version>4.1.1</version></parent>
  <properties><java.version>27</java.version><mockito.version>5.24.0</mockito.version></properties>
  <!-- <dependency><groupId>x</groupId><artifactId>bortkommenterad</artifactId><version>1.0</version></dependency> -->
  <dependencies>
    <dependency><groupId>org.jsoup</groupId><artifactId>jsoup</artifactId><version>1.23.2</version></dependency>
    <dependency><groupId>org.mockito</groupId><artifactId>mockito-core</artifactId><version>\${mockito.version}</version></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId></dependency>
  </dependencies>
  <build><plugins><plugin><artifactId>maven-surefire-plugin</artifactId><version>3.5.4</version></plugin></plugins></build>
</project>`;
const las = v.lasPom(pom);
const namn = las.beroenden.map(b => b.a + ':' + b.v);
prov('parent med', namn.includes('spring-boot-starter-parent:4.1.1'), namn);
prov('egenskap upplöst', namn.includes('mockito-core:5.24.0'), namn);
prov('version från parent hoppas över', !namn.some(n => n.startsWith('spring-boot-starter-web')), namn);
prov('kommentar räknas inte', !namn.some(n => n.startsWith('bortkommenterad')), namn);
prov('plugin utan groupId får maven-plugins', las.beroenden.some(b => b.a === 'maven-surefire-plugin' && b.g === 'org.apache.maven.plugins'), JSON.stringify(las.beroenden));
prov('Java-versionen läses', las.java === '27', las.java);

const pkg = v.lasPackage('{"engines":{"node":">=24"},"dependencies":{"express":"^5.2.1"},"devDependencies":{"lokal":"file:../x"}}');
prov('npm: ^ tas bort', pkg.beroenden.length === 1 && pkg.beroenden[0].v === '5.2.1', JSON.stringify(pkg.beroenden));
prov('npm: node-versionen läses', pkg.node === '>=24', pkg.node);

console.log(fel ? `\n${fel} FEL` : '\nalla prov gröna');
process.exit(fel ? 1 : 0);

# Java 27 pa Eclipse Temurin. Bygget gick 2026-09-22--10-05 pa Liberica eftersom Temurin
# saknade 27-avbildningar; eclipse-temurin:27-jdk/-jre finns nu (kontrollerat 2026-10-05).
#
# Maven kommer fran wrappern i repot i stallet for fran basavbildningen:
# maven:3.9-eclipse-temurin-27 finns fortfarande inte. Wrappern pinnar 3.9.16 (samma version som
# byggdes lokalt) och hamtar den sjalv - den klarar sig med wget, curl ELLER bara java,
# sa den staller inga krav pa vad basavbildningen rakar ha installerat.
FROM eclipse-temurin:27-jdk AS build
WORKDIR /app
COPY pom.xml .
COPY .mvn ./.mvn
COPY mvnw .
# chmod trots att .gitattributes och git-laget (100755) redan sager ratt sak: skriptet
# skrivs pa en Windows-maskin, och rattigheten ar det billigaste stallet att vara sakter pa.
RUN chmod +x mvnw && ./mvnw -B dependency:go-offline
COPY src ./src
RUN ./mvnw -B clean package -DskipTests

FROM eclipse-temurin:27-jre
WORKDIR /app
COPY --from=build /app/target/caradvice-1.0.0.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]

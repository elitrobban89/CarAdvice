# Java 27 (GA 2026-09-15). Temurin har ANNU inte publicerat nagra 27-avbildningar -
# varken eclipse-temurin:27-jdk/jre eller maven:3.9-eclipse-temurin-27 finns pa Docker Hub
# (kontrollerat 2026-09-22, 404 pa alla tre). Liberica publicerar varje utgava, LTS eller ej,
# och har bade JDK och JRE for 27. Byt tillbaka till Temurin nar deras 27 dyker upp: det ar
# ett namnbyte pa tva rader, och byggsteget kan da ateranvanda maven-avbildningen igen.
#
# Maven kommer fran wrappern i repot i stallet for fran basavbildningen, av samma skal:
# det finns ingen maven-avbildning med JDK 27 an. Wrappern pinnar 3.9.16 (samma version som
# byggdes lokalt) och hamtar den sjalv - den klarar sig med wget, curl ELLER bara java,
# sa den staller inga krav pa vad basavbildningen rakar ha installerat.
FROM bellsoft/liberica-openjdk-debian:27 AS build
WORKDIR /app
COPY pom.xml .
COPY .mvn ./.mvn
COPY mvnw .
RUN ./mvnw -B dependency:go-offline
COPY src ./src
RUN ./mvnw -B clean package -DskipTests

FROM bellsoft/liberica-openjre-debian:27
WORKDIR /app
COPY --from=build /app/target/caradvice-1.0.0.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]

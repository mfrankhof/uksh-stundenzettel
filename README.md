# UKSH-Stundenzettel-Tool

Dieses Tool füllt den Zeitnachweis (Stundenzettel) für studentische Hilfskräfte des Universitätsklinikums Schleswig-Holstein (UKSH) auf Basis einer Excel-Datei aus.

## Excel-Datei

Die Excel-Arbeitsmappe kann beliebig modifiziert werden, solange folgende Anforderungen erfüllt bleiben:
1. Es muss ein Tabellenblatt mit dem Namen "Einträge" vorhanden sein.
2. Dieses Blatt muss eine Tabelle mit dem Namen "Einträge" enthalten.
3. Die "Einträge"-Tabelle muss folgende Spalten enthalten:
   1. Datum
   2. Beginn
   3. Ende
   4. Bemerkung
4. Es müssen folgende benannte Bereiche mit dem Gültigkeitsbereich *Arbeitsmappe* vorhanden sein:
   1. Vorname
   2. Nachname
   3. Personalnummer
   4. Einsatzbereich

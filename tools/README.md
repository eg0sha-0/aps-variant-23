# Проверка и рендеринг диаграмм

Все исходники самостоятельны: внешних include нет.
Повторный рендеринг PlantUML через публичный сервер Kroki:

```sh
python tools/render_diagrams.py
```

Требуется Python 3 и интернет, дополнительных Python-пакетов нет.
Скрипт передаёт только содержимое `docs/diagrams/*.puml` серверу
`https://kroki.io/plantuml/`. SVG и PNG сохраняются рядом с исходниками.
При ошибке HTTP, невалидном XML или ответе об ошибке диаграммы завершение неуспешно.

Для полностью локальной работы с установленными Java и официальным PlantUML:

```sh
java -jar plantuml.jar -checkonly docs/diagrams/*.puml
java -jar plantuml.jar -tsvg docs/diagrams/*.puml
java -jar plantuml.jar -tpng docs/diagrams/*.puml
```

При необходимости диаграмма классов требует Graphviz. Бинарные зависимости не включены.
Первичная проверка опубликованных изображений описана в [verification.md](../docs/verification.md).

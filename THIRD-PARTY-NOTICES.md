# Third-party notices

TradeRouter.Java embeds transformed third-party geographic data. The project licence does not replace the terms that apply to those inputs.

## searoute-py 1.6.0

The embedded maritime network and port list are transformed from files distributed by [genthalili/searoute-py](https://github.com/genthalili/searoute-py/tree/1.6.0).

Copyright 2024 Gent Halili. Distributed under the [Apache License 2.0](https://github.com/genthalili/searoute-py/blob/1.6.0/LICENCE.txt). The embedded files are modified into compact JSON/gzip form; see `DATA_PROVENANCE.md` and the scripts in `tools/`.

searoute-py credits [Eurostat SeaRoute](https://github.com/eurostat/searoute) for the maritime network. The Eurostat repository is licensed under EUPL-1.2 and documents the earlier Oak Ridge shipping-lane and AIS-derived inputs.

## UN/LOCODE

The embedded location-code data is published by the [United Nations Economic Commission for Europe](https://unlocode.unece.org/). UNECE states that UN/CEFACT standards are free to use under [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/).

The coordinate supplement identifies its source in each JSON row. The five hand-reviewed entries cite:

- [Gatwick Airport](https://en.wikipedia.org/wiki/Gatwick_Airport)
- [Shanghai railway station](https://en.wikipedia.org/wiki/Shanghai_railway_station)
- [Shanghai Hongqiao International Airport](https://en.wikipedia.org/wiki/Shanghai_Hongqiao_International_Airport)
- [Melrose, South Australia](https://en.wikipedia.org/wiki/Melrose,_South_Australia)
- [Guildford](https://wiki.openstreetmap.org/wiki/Guildford)

## GeoNames

The GeoNames entries in the same UN/LOCODE coordinate supplement are derived from the [GeoNames gazetteer downloads](https://download.geonames.org/export/dump/), specifically `alternateNamesV2.zip` and `allCountries.zip` dated 16 September 2026. GeoNames is licensed under [Creative Commons Attribution 4.0](https://creativecommons.org/licenses/by/4.0/). Each included row retains its GeoNames identifier; the [generation script](tools/build-unlocode-coordinate-supplement.py) and [data provenance](DATA_PROVENANCE.md) document the filters and exact source hashes. Data is provided as is without a warranty of accuracy.

# Third-party notices

TradeRouter.Java embeds transformed third-party geographic data. The project licence does not replace the terms that apply to those inputs.

## searoute-py 1.6.0

The embedded maritime network and port list are transformed from files distributed by [genthalili/searoute-py](https://github.com/genthalili/searoute-py/tree/1.6.0).

Copyright 2024 Gent Halili. Distributed under the [Apache License 2.0](https://github.com/genthalili/searoute-py/blob/1.6.0/LICENCE.txt). The embedded files are modified into compact JSON/gzip form; see `DATA_PROVENANCE.md` and the scripts in `tools/`.

searoute-py credits [Eurostat SeaRoute](https://github.com/eurostat/searoute) for the maritime network. The Eurostat repository is licensed under the [European Union Public Licence 1.2](https://github.com/eurostat/searoute/blob/master/LICENSE) and documents the earlier Oak Ridge shipping-lane and AIS-derived inputs.

The embedded Marnet network is therefore derived from material that Eurostat publishes under EUPL-1.2. searoute-py distributes its copy under Apache-2.0, and TradeRouter.Java takes the network from that distribution. The Apache-2.0 licence on TradeRouter.Java's code does not override any EUPL-1.2 terms that apply to the network. Users who redistribute the embedded maritime data should review EUPL-1.2 alongside this notice.

## North American Rail Network

The embedded rail graph is transformed from the [North American Rail Network Lines](https://doi.org/10.21949/1528950) in the U.S. Department of Transportation Bureau of Transportation Statistics National Transportation Atlas Database, compiled from Federal Railroad Administration data. It is a work of the United States government under 17 U.S.C. § 101, not protected by U.S. copyright and available for unrestricted public use. The graph keeps the main line only and simplifies its geometry; see `DATA_PROVENANCE.md` and the scripts in `tools/`.

## Natural Earth

The embedded sea-area polygons are taken unchanged from the [Natural Earth](https://www.naturalearthdata.com/) 1:50m `ne_50m_geography_marine_polys` layer, as published in [nvkelso/natural-earth-vector](https://github.com/nvkelso/natural-earth-vector) at commit `117488dc88`. Natural Earth is in the public domain; see its [terms of use](https://www.naturalearthdata.com/about/terms-of-use/). Only 43 features are kept; see `DATA_PROVENANCE.md` and `tools/build-sea-area-data.py`.

## UN/LOCODE

The embedded location-code data is published by the [United Nations Economic Commission for Europe](https://unlocode.unece.org/). UNECE states that UN/CEFACT standards are free to use under [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/).

The coordinate supplement identifies its source in each JSON row. The seven hand-reviewed entries cite:

- [Gatwick Airport](https://en.wikipedia.org/wiki/Gatwick_Airport)
- [Shanghai railway station](https://en.wikipedia.org/wiki/Shanghai_railway_station)
- [Shanghai Hongqiao International Airport](https://en.wikipedia.org/wiki/Shanghai_Hongqiao_International_Airport)
- [Melrose, South Australia](https://en.wikipedia.org/wiki/Melrose,_South_Australia)
- [Guildford](https://wiki.openstreetmap.org/wiki/Guildford)
- [Port of Montreal](https://en.wikipedia.org/wiki/Port_of_Montreal)
- [Tanger Med](https://en.wikipedia.org/wiki/Tanger_Med)

## World Port Index

Sea-port rows and reviewed corrections in the UN/LOCODE coordinate supplement whose `source` names the World Port Index take their position from the [World Port Index](https://msi.nga.mil/Publications/WPI) (Pub. 150), published by the US National Geospatial-Intelligence Agency. As a work of the US Government it is in the public domain.

## Wikidata

Sea-port rows whose `source` names a Wikidata item take their position from [Wikidata](https://www.wikidata.org/), whose structured data is published under [CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/).

## GeoNames

The GeoNames entries in the same UN/LOCODE coordinate supplement are derived from the [GeoNames gazetteer downloads](https://download.geonames.org/export/dump/), specifically `alternateNamesV2.zip` and `allCountries.zip` dated 16 September 2026. GeoNames is licensed under [Creative Commons Attribution 4.0](https://creativecommons.org/licenses/by/4.0/). Each included row retains its GeoNames identifier; the [generation script](tools/build-unlocode-coordinate-supplement.py) and [data provenance](DATA_PROVENANCE.md) document the filters and exact source hashes. Sea-port rows whose `source` begins `Port fill, GeoNames` come from the GeoNames per-country dumps and alternate names retrieved on 29 October 2026 by [another script](tools/fill-sea-port-coordinates.py), with hashes pinned in [its manifest](tools/sea-port-coordinate-sources.json). Data is provided as is without a warranty of accuracy.

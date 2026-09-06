package ch.lab77.radar.data

/**
 * Classification par nom de fabricant. Volontairement lisible : une ligne par famille,
 * à enrichir au fil des relevés terrain. Ordre = priorité de correspondance.
 */
object Classifier {
    private val rules: List<Pair<Category, Regex>> = listOf(
        Category.CELLULAR_ROUTER to Regex(
            "cradlepoint|sierra wireless|teltonika|peplink|pepwave|inseego|novatel|digi international|" +
            "robustel|inhand|milesight|four-faith|netgear.*(lte|5g)|zte|quectel|telit|u-blox|fibocom|" +
            "mikrotik.*lte|huawei.*(lte|5g)|starlink|space exploration|jaguar network|calamp", RegexOption.IGNORE_CASE
        ),
        Category.CAMERA to Regex(
            "hikvision|hangzhou hikvision|dahua|axis communications|hanwha|samsung techwin|uniview|" +
            "vivotek|reolink|arlo|ring|wyze|eufy|anker.*cam|verkada|avigilon|flir|mobotix|geovision|" +
            "lorex|swann|amcrest|tp-link.*tapo|ubiquiti.*(video|unifi protect)|bosch security", RegexOption.IGNORE_CASE
        ),
        Category.FLEET to Regex(
            "geotab|samsara|calamp|queclink|teltonika|ruptela|trackimo|mix telematics|omnicomm|" +
            "concox|jimi|fleet complete|verizon connect|lytx|smartdrive|netradyne|zonar|trimble|" +
            "garmin.*fleet|sensata|orbcomm|iridium|globalstar|motorola solutions|axon|harris|l3harris|" +
            "sepura|hytera|kenwood|tait", RegexOption.IGNORE_CASE
        ),
        Category.NETWORK_INFRA to Regex(
            "cisco|meraki|aruba|hewlett packard enterprise|hpe|ubiquiti|ubnt|ruckus|commscope|" +
            "juniper|mist systems|extreme networks|fortinet|cambium|mikrotik|routerboard|" +
            "tp-link|zyxel|netgear|d-link|engenius|edgecore|edge-core|arista|huawei technologies|" +
            "nokia|ericsson|alcatel|telrad|siklu|ceragon|airspan|baicells|sonicwall|watchguard|" +
            "palo alto|aerohive|lancom|draytek|grandstream|aviat", RegexOption.IGNORE_CASE
        ),
        Category.INDUSTRIAL to Regex(
            "siemens|schneider|rockwell|allen-bradley|abb|honeywell|emerson|yokogawa|phoenix contact|" +
            "moxa|advantech|beckhoff|wago|b&r|omron|mitsubishi electric|fanuc|kuka|bosch rexroth|" +
            "hms industrial|prosoft|red lion|weidm|pepperl|sick ag|ifm electronic|balluff|festo|" +
            "danfoss|grundfos|carrier|trane|daikin|johnson controls|itron|landis|kamstrup|sagemcom.*energy|" +
            "espressif|raspberry|particle|arduino|seeed|heltec|lilygo|rakwireless|semtech|multitech|" +
            "the things|kerlink|dragino|milesight|laird|silicon lab|nordic semi|texas instruments", RegexOption.IGNORE_CASE
        ),
        Category.CONSUMER to Regex(
            "apple|samsung|google|xiaomi|huawei|oppo|vivo|oneplus|realme|motorola mobility|sony|lg electronics|" +
            "nokia mobile|hmd|amazon|microsoft|intel|dell|lenovo|asus|acer|hp inc|msi|razer|" +
            "bose|jbl|harman|sonos|garmin|fitbit|polar|suunto|tile|logitech|corsair|steelseries|" +
            "roku|nintendo|valve|oculus|meta platforms|gopro|dji|sagemcom|technicolor|arris|" +
            "askey|azurewave|liteon|murata|foxconn|hon hai|wistron|compal|quanta|pegatron|" +
            "ampak|realtek|mediatek|broadcom|qualcomm|espressif|tuya|shenzhen", RegexOption.IGNORE_CASE
        ),
    )

    fun classify(vendorLong: String, kind: Kind, mac: String): Category {
        if (vendorLong.isBlank()) {
            return if (kind == Kind.WIFI && Oui.isLocallyAdministered(mac)) Category.RANDOMIZED
            else if (kind == Kind.BLE && Oui.bleAddressType(mac) != "publique/non-résolvable") Category.RANDOMIZED
            else Category.UNKNOWN
        }
        for ((cat, rx) in rules) if (rx.containsMatchIn(vendorLong)) return cat
        return Category.UNKNOWN
    }
}

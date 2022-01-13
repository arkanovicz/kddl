parser grammar kddlParser;

options { tokenVocab = kddlLexer; }

database: DATABASE name=LABEL LC ( schema | link ) * RC ;
schema: SCHEMA name=LABEL LC ( table | link )* RC ;
table: TABLE name=LABEL ( FS par=qualified direction? )? ( LC field* RC )?;
direction: LP ( UP | DOWN | LEFT | RIGHT ) RP;
link: left=qualified ( left_optional=QM )? ( left_mult=ST | left_single=LA )? MN+ ( right_mult=ST | right_single=RA )? right=qualified ( right_optional=QM )? CASCADE? direction? ;
field: ( pk=ST | unique=EM )? name=LABEL (type ( optional=QM )? default? | MN+ RA reference=qualified ( optional=QM )? CASCADE? direction? ) ;
type: BOOLEAN
    | INT
    | SERIAL
    | LONG
    | FLOAT
    | DOUBLE
    | MONEY
    | NUMERIC LP prec=INTEGER ( CM scale=INTEGER )? RP
    | TIME
    | DATE
    | DATETZ
    | DATETIME
    | DATETIMETZ
    | INTERVAL
    | CHAR ( LP width=INTEGER RP )?
    | VARCHAR ( LP ( width=INTEGER )? RP )?
    | TEXT
    | ENUM LP value=STRING ( CM? value=STRING )* RP
    | JSON
    | JSONB ;
default: EQ expression ;
expression: NULL | boolean | number | STRING | function ;
boolean: TRUE | FALSE ;
number: MN? INTEGER ( DOT frac=INTEGER )? ;
function: name=LABEL LP (arg=.)*? RP;
qualified: ( ref_schema=LABEL DOT )? name=LABEL ;

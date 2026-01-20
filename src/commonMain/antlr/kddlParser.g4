parser grammar kddlParser;

options { tokenVocab = kddlLexer; }

database: include_stmt* DATABASE name=LABEL LC ( schema | link | option ) * RC ;
include_stmt: INCLUDE path=STRING ;
schema: SCHEMA name=LABEL LC ( enum_decl | table | link )* RC ;
enum_decl: ENUM name=LABEL LP enum_value ( CM? enum_value )* RP ;
enum_value: STRING | LABEL ;
table: TABLE name=LABEL ( FS par=qualified direction? )? ( LC field* RC )?;
direction: LP ( UP | DOWN | LEFT | RIGHT ) RP;
link: linkElement (connector linkElement)+ CASCADE? direction? ;
linkElement: ref=qualified (optional=QM)? ;
connector: (left_mult=ST | left_single=LA)? MN+ (right_mult=ST | right_single=RA)? ;
field: ( pk=ST | unique=EM | indexed=PL )? name=identifier (type ( optional=QM )? ( AS alias=LABEL )? default? | default | MN+ RA reference=qualified ( optional=QM )? CASCADE? direction? ) ;
identifier: LABEL | BOOLEAN | BIGINT | INT | SMALLINT | SERIAL | LONG | FLOAT | DOUBLE
          | MONEY | NUMERIC | TIME | TIMETZ | DATE | TIMESTAMP | TIMESTAMPTZ | INTERVAL
          | CHAR | VARCHAR | TEXT | BLOB | ENUM | UUID | JSON | VARBIT ;
type: BOOLEAN
    | BIGINT
    | INT
    | SMALLINT
    | SERIAL
    | LONG
    | FLOAT
    | DOUBLE
    | MONEY
    | NUMERIC ( LP prec=INTEGER ( CM scale=INTEGER )? RP )?
    | TIME
    | TIMETZ
    | DATE
    | TIMESTAMP ( LP prec=INTEGER RP )?
    | TIMESTAMPTZ ( LP prec=INTEGER RP )?
    | INTERVAL
    | CHAR ( LP width=INTEGER RP )?
    | VARCHAR ( LP ( width=INTEGER )? RP )?
    | TEXT
    | BLOB
    | ENUM LP enum_value ( CM? enum_value )* RP
    | UUID
    | JSON
    | VARBIT ( LP ( width=INTEGER )? RP )?
    | enum_ref=LABEL ;
default: EQ expression ;
expression: NULL | boolean | number | STRING | function ;
boolean: TRUE | FALSE ;
number: MN? INTEGER ( DOT frac=INTEGER )? ;
// function: name=LABEL LP (arg=[^\\)]*)? RP; Parsing problem - CB TODO
function: name=LABEL LP arglist? RP;
qualified: ( ref_schema=LABEL DOT )? name=LABEL ;
arglist: label_or_expr ( CM label_or_expr )* ;
label_or_expr: label=LABEL | expr=expression ;
option: OPTION name=LABEL EQ value=STRING ;

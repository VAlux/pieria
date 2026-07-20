; Scala 2/3 definitions and relations.
(package_clause name: (package_identifier) @def.name) @def.package
(trait_definition name: (identifier) @def.name) @def.interface
(enum_definition name: (identifier) @def.name) @def.enum
(class_definition name: (identifier) @def.name) @def.class
(object_definition name: (identifier) @def.name) @def.class
(type_definition name: (type_identifier) @def.name) @def.type_alias
(function_definition name: (identifier) @def.name) @def.function
(template_body (function_definition name: (identifier) @def.name) @def.method)
(val_definition pattern: (identifier) @def.name) @def.variable
(var_definition pattern: (identifier) @def.name) @def.variable

(import_declaration path: (identifier) @ref.name) @ref.import
(call_expression (identifier) @ref.name) @ref.call
(extends_clause (type_identifier) @ref.name) @ref.extends

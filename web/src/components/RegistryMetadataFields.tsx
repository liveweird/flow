import type { ReactNode } from "react";
import { Textarea, TextInput, type TextareaProps, type TextInputProps } from "@mantine/core";
import { useTranslation } from "react-i18next";
import { charCountDescription } from "../utils/charCount";

/** Shared name/description controls for a registry editor (currently Teams). */
export default function RegistryMetadataFields({
  nameMaxLength,
  descriptionMaxLength,
  descriptionLength,
  nameInputProps,
  descriptionInputProps,
  beforeName,
}: {
  nameMaxLength: number;
  descriptionMaxLength: number;
  descriptionLength: number;
  nameInputProps: TextInputProps;
  descriptionInputProps: TextareaProps;
  beforeName?: ReactNode;
}) {
  const { t } = useTranslation();

  return (
    <>
      {beforeName}
      <TextInput
        label={t("common.field.name")}
        maxLength={nameMaxLength}
        data-autofocus
        {...nameInputProps}
      />
      <Textarea
        label={t("common.field.description")}
        autosize
        minRows={2}
        maxLength={descriptionMaxLength}
        description={charCountDescription(descriptionLength, descriptionMaxLength)}
        inputWrapperOrder={["label", "input", "description", "error"]}
        {...descriptionInputProps}
      />
    </>
  );
}

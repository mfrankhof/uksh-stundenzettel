<script lang="ts">
  import { Button } from "$lib/components/ui/button/index";
  import { Input } from "$lib/components/ui/input/index";
  import * as Select from "$lib/components/ui/select/index";
  import * as Item from "$lib/components/ui/item/index";
  import { parseXlsx, type Timesheet } from "$lib/xslxParser";
  import { generatePdf } from "$lib/pdfGenerator";
  import { Effect, Either } from "effect";
  import DownloadIcon from "@lucide/svelte/icons/download";
  import FileTextIcon from "@lucide/svelte/icons/file-text";
  import FileSpreadsheetIcon from "@lucide/svelte/icons/file-spreadsheet";
  import UploadIcon from "@lucide/svelte/icons/upload";
  import XIcon from "@lucide/svelte/icons/x";
  import CircleAlertIcon from "@lucide/svelte/icons/circle-alert";
  import * as Field from "$lib/components/ui/field/index";
  import * as Card from "$lib/components/ui/card/index";
  import * as Alert from "$lib/components/ui/alert/index";
  import * as AlertDialog from "$lib/components/ui/alert-dialog/index";
  import { Spinner } from "$lib/components/ui/spinner/index";
  import { cn } from "$lib/utils";
  import { tick } from "svelte";

  const XLSX_MIME =
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

  const MONTH_NAMES = [
    "Januar",
    "Februar",
    "März",
    "April",
    "Mai",
    "Juni",
    "Juli",
    "August",
    "September",
    "Oktober",
    "November",
    "Dezember",
  ];
  // bits-ui Select works with string values, so the month is held as a 1-based string.
  const months = MONTH_NAMES.map((label, index) => ({
    value: String(index + 1),
    label,
  }));

  const now = new Date();
  let year = $state(now.getFullYear());
  let month = $state(String(now.getMonth() + 1));
  let fileInput: HTMLInputElement;
  let xslxFile = $state<File | null>(null);
  let pdfBytes = $state<Uint8Array | null>(null);
  let isDragging = $state(false);
  let isGenerating = $state(false);
  let uploadButton = $state<HTMLElement | null>(null);
  let error = $state<string | null>(null);
  let confirmEmptyOpen = $state(false);
  let pendingTimesheet = $state<Timesheet | null>(null);

  const selectedMonthLabel = $derived(
    months.find((m) => m.value === month)?.label,
  );
  const pdfFilename = $derived(
    `Stundenzettel_${year}-${month.padStart(2, "0")}.pdf`,
  );
  // The number input binds to null when cleared, so guard against that too.
  const canGenerate = $derived(
    xslxFile !== null && Number.isFinite(year) && month !== "",
  );

  // A generated PDF (and any error) is tied to the chosen period, so discard them
  // when either changes.
  $effect(() => {
    year;
    month;
    pdfBytes = null;
    error = null;
  });

  function setFile(file: File | null) {
    xslxFile = file;
    pdfBytes = null;
    error = null;
  }

  async function removeFile() {
    setFile(null);
    // The drop zone (and its upload button) re-mounts once the file is gone;
    // wait for that, then return focus so keyboard users aren't stranded.
    await tick();
    uploadButton?.focus();
  }

  function isXlsx(file: File) {
    return file.name.toLowerCase().endsWith(".xlsx") || file.type === XLSX_MIME;
  }

  function handleChange(event: Event) {
    const target = event.currentTarget as HTMLInputElement;
    setFile(target.files?.[0] ?? null);
  }

  function handleDragOver(event: DragEvent) {
    event.preventDefault();
    if (event.dataTransfer) event.dataTransfer.dropEffect = "copy";
    isDragging = true;
  }

  function handleDragLeave(event: DragEvent) {
    // dragleave also fires when moving onto a child element, so ignore those.
    const next = event.relatedTarget;
    if (
      next instanceof Node &&
      event.currentTarget instanceof Node &&
      event.currentTarget.contains(next)
    ) {
      return;
    }
    isDragging = false;
  }

  function handleDrop(event: DragEvent) {
    event.preventDefault();
    isDragging = false;
    const file = event.dataTransfer?.files?.[0];
    if (!file) return;
    if (!isXlsx(file)) {
      error = "Bitte eine Excel-Datei (.xlsx) auswählen.";
      return;
    }
    setFile(file);
  }

  async function handleGeneratePdf() {
    if (!xslxFile) return;
    isGenerating = true;
    error = null;
    try {
      // `Effect.either` surfaces the typed failure as a value instead of a
      // rejected promise, so the parser's German messages reach the UI.
      const parsed = await Effect.runPromise(
        Effect.either(parseXlsx(xslxFile, year, Number(month))),
      );
      if (Either.isLeft(parsed)) {
        error = parsed.left.message;
        return;
      }
      // An empty period would yield a blank timesheet, so confirm before generating.
      if (parsed.right.entries.length === 0) {
        pendingTimesheet = parsed.right;
        confirmEmptyOpen = true;
        return;
      }
      await renderPdf(parsed.right);
    } finally {
      isGenerating = false;
    }
  }

  async function confirmGenerateEmpty() {
    const timesheet = pendingTimesheet;
    pendingTimesheet = null;
    confirmEmptyOpen = false;
    if (!timesheet) return;
    isGenerating = true;
    try {
      await renderPdf(timesheet);
    } finally {
      isGenerating = false;
    }
  }

  async function renderPdf(timesheet: Timesheet) {
    const generated = await Effect.runPromise(
      Effect.either(generatePdf(timesheet, year, Number(month))),
    );
    if (Either.isLeft(generated)) {
      error = generated.left.message;
    } else {
      pdfBytes = generated.right;
    }
  }

  function downloadPdf() {
    if (!pdfBytes) return;
    const url = URL.createObjectURL(
      new Blob([pdfBytes as Uint8Array<ArrayBuffer>], {
        type: "application/pdf",
      }),
    );
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = pdfFilename;
    anchor.click();
    URL.revokeObjectURL(url);
  }
</script>

<input
  bind:this={fileInput}
  type="file"
  accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
  class="hidden"
  onchange={handleChange}
/>

<div
  class="bg-muted flex min-h-svh flex-col items-center justify-center gap-6 p-6"
>
  <Card.Root class="w-full max-w-md">
    <Card.Content class="flex flex-col gap-6">
      <div class="flex flex-col gap-3">
        {#if xslxFile}
          <Item.Root variant="muted">
            <Item.Media variant="icon">
              <FileSpreadsheetIcon />
            </Item.Media>
            <Item.Content>
              <Item.Title>{xslxFile.name}</Item.Title>
              <Item.Description
                >{(xslxFile.size / 1024).toFixed(1)} KB</Item.Description
              >
            </Item.Content>
            <Item.Actions>
              <Button
                size="icon"
                variant="ghost"
                aria-label="Datei entfernen"
                onclick={removeFile}
              >
                <XIcon />
              </Button>
            </Item.Actions>
          </Item.Root>
        {:else}
          <div
            role="region"
            aria-label="Excel-Datei hochladen"
            ondragover={handleDragOver}
            ondragleave={handleDragLeave}
            ondrop={handleDrop}
            class={cn(
              "flex flex-col items-center justify-center gap-3 rounded-lg border-2 border-dashed p-8 text-center transition-colors",
              isDragging ? "border-primary bg-accent/50" : "border-input",
            )}
          >
            <Button
              bind:ref={uploadButton}
              size="icon"
              variant="ghost"
              aria-label="Datei auswählen"
              onclick={() => fileInput.click()}
            >
              <UploadIcon />
            </Button>
            <p class="text-muted-foreground text-sm">
              Excel-Datei hierher ziehen oder auf das Symbol klicken
            </p>
          </div>

          <a
            href="/stundenzettel-vorlage.xlsx"
            download
            class="text-muted-foreground hover:text-foreground inline-flex items-center gap-1.5 self-center text-sm underline-offset-4 hover:underline"
          >
            <DownloadIcon class="size-4" />
            Excel-Vorlage herunterladen
          </a>
        {/if}
      </div>

      <Field.Group>
        <div class="grid grid-cols-2 gap-4">
          <Field.Field>
            <Field.Label for="year">Jahr</Field.Label>
            <Input
              id="year"
              type="number"
              bind:value={year}
              min={2000}
              max={2100}
            />
          </Field.Field>
          <Field.Field>
            <Field.Label for="month">Monat</Field.Label>
            <Select.Root type="single" bind:value={month}>
              <Select.Trigger class="w-full"
                >{selectedMonthLabel}</Select.Trigger
              >
              <Select.Content>
                {#each months as monthOption (monthOption.value)}
                  <Select.Item
                    value={monthOption.value}
                    label={monthOption.label}
                  >
                    {monthOption.label}
                  </Select.Item>
                {/each}
              </Select.Content>
            </Select.Root>
          </Field.Field>
        </div>
      </Field.Group>

      {#if error}
        <Alert.Root variant="destructive">
          <CircleAlertIcon />
          <Alert.Title>Fehler</Alert.Title>
          <Alert.Description>{error}</Alert.Description>
        </Alert.Root>
      {/if}
    </Card.Content>
    <Card.Footer>
      <Button
        class="w-full"
        disabled={!canGenerate || isGenerating}
        onclick={handleGeneratePdf}
      >
        {#if isGenerating}
          <Spinner />
        {/if}
        PDF erzeugen
      </Button>
    </Card.Footer>
  </Card.Root>

  {#if pdfBytes}
    <Item.Root
      class="bg-card ring-foreground/10 w-full max-w-md rounded-xl px-4 py-4 ring-1"
    >
      <Item.Media variant="icon">
        <FileTextIcon />
      </Item.Media>
      <Item.Content>
        <Item.Title>{pdfFilename}</Item.Title>
        <Item.Description
          >{(pdfBytes.length / 1024).toFixed(1)} KB</Item.Description
        >
      </Item.Content>
      <Item.Actions>
        <Button
          size="icon"
          variant="ghost"
          aria-label="PDF herunterladen"
          onclick={downloadPdf}
        >
          <DownloadIcon />
        </Button>
      </Item.Actions>
    </Item.Root>
  {/if}
</div>

<AlertDialog.Root bind:open={confirmEmptyOpen}>
  <AlertDialog.Content>
    <AlertDialog.Header>
      <AlertDialog.Title>Keine Einträge gefunden</AlertDialog.Title>
      <AlertDialog.Description>
        Für {selectedMonthLabel}
        {year} wurden keine Einträge gefunden. Möchten Sie trotzdem einen leeren
        Stundenzettel erzeugen?
      </AlertDialog.Description>
    </AlertDialog.Header>
    <AlertDialog.Footer>
      <AlertDialog.Cancel
        onclick={() => {
          pendingTimesheet = null;
          confirmEmptyOpen = false;
        }}
      >
        Abbrechen
      </AlertDialog.Cancel>
      <AlertDialog.Action onclick={confirmGenerateEmpty}>
        Trotzdem erzeugen
      </AlertDialog.Action>
    </AlertDialog.Footer>
  </AlertDialog.Content>
</AlertDialog.Root>
